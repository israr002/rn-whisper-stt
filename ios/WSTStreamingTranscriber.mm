#import "WSTStreamingTranscriber.h"

#import <AVFoundation/AVFoundation.h>
#import <atomic>
#import <deque>
#import <vector>

#import "WSTVad.h"

static const int kSampleRate = 16000;

@implementation WSTStreamingConfig
- (instancetype)init {
  if ((self = [super init])) {
    _speechThreshold = 0.45f;
    _silenceThreshold = 0.3f;
    _endSilenceMs = 700;
    _minUtteranceMs = 1000;
    _maxHoldSilenceMs = 2000;
    _minSpeechMs = 250;
    _carryWindowMs = 2000;
    _maxUtteranceMs = 20000;
    _preRollMs = 800;
    _livePartials = YES;
    _partialIntervalMs = 600;
    _speculativeSilenceMs = 200;
  }
  return self;
}
@end

static double NowMs(void) {
  return CACurrentMediaTime() * 1000.0;
}

@implementation WSTStreamingTranscriber {
  WSTEngine *_engine;
  dispatch_queue_t _worker;
  dispatch_queue_t _audioQueue; // VAD + segmenting, off the real-time audio thread
  NSString *_vadModelPath;
  WSTStreamingConfig *_config;
  WSTTranscriptHandler _onTranscript;
  WSTErrorHandler _onError;

  AVAudioEngine *_audioEngine;
  AVAudioConverter *_converter;
  AVAudioFormat *_targetFormat;
  WSTVad *_vad;
  std::atomic<bool> _running;
  std::atomic<bool> _workerBusy;

  // --- Segmenter state: _audioQueue only ---
  std::vector<float> _pending; // resampled audio not yet fed to the VAD
  std::deque<std::vector<float>> _preRoll;
  std::vector<float> _audio;
  BOOL _active, _inSpeech;
  int _speechMs, _trailingSilenceMs, _segmentId;
  size_t _speechEnd;       // audio length right after the last speech chunk
  long _submittedSpeechEnd;
  double _lastPartialAt;
  // A too-short burst (e.g. "ask not" under noise) waiting to be prepended to the next utterance
  std::vector<float> _carry;
  int _carrySpeechMs, _chunksSinceCarry;

  // --- Last result: _worker only ---
  int _cachedId;
  size_t _cachedSpeechEnd;
  NSString *_cachedText;
}

- (instancetype)initWithEngine:(WSTEngine *)engine
                        worker:(dispatch_queue_t)worker
                  vadModelPath:(NSString *)vadModelPath
                        config:(WSTStreamingConfig *)config
                  onTranscript:(WSTTranscriptHandler)onTranscript
                       onError:(WSTErrorHandler)onError {
  if ((self = [super init])) {
    _engine = engine;
    _worker = worker;
    _vadModelPath = vadModelPath;
    _config = config;
    _onTranscript = onTranscript;
    _onError = onError;
    _audioQueue = dispatch_queue_create("WhisperAudio", dispatch_queue_attr_make_with_qos_class(
                                                          DISPATCH_QUEUE_SERIAL, QOS_CLASS_USER_INITIATED, 0));
    _running = false;
    _workerBusy = false;
    _cachedId = -1;
    _cachedText = @"";
  }
  return self;
}

#pragma mark - Capture

- (BOOL)start:(NSString **)errorMessage {
  if (_running) return YES;
  _vad = [[WSTVad alloc] initWithModelPath:_vadModelPath];
  if (!_vad) {
    if (errorMessage) *errorMessage = @"Could not load the VAD model";
    return NO;
  }

  NSError *error = nil;
  AVAudioSession *session = AVAudioSession.sharedInstance;
  // .measurement turns off the system's voice processing (AGC, noise suppression): raw audio is
  // closest to what Whisper was trained on. playAndRecord + mixing keeps other app audio working.
  if (![session setCategory:AVAudioSessionCategoryPlayAndRecord
                       mode:AVAudioSessionModeMeasurement
                    options:AVAudioSessionCategoryOptionDefaultToSpeaker |
                            AVAudioSessionCategoryOptionAllowBluetoothHFP |
                            AVAudioSessionCategoryOptionMixWithOthers
                      error:&error] ||
      ![session setActive:YES error:&error]) {
    if (errorMessage) *errorMessage = [NSString stringWithFormat:@"Audio session: %@", error.localizedDescription];
    return NO;
  }

  _audioEngine = [AVAudioEngine new];
  AVAudioInputNode *input = _audioEngine.inputNode;
  AVAudioFormat *inputFormat = [input outputFormatForBus:0];
  if (inputFormat.sampleRate == 0) {
    if (errorMessage) *errorMessage = @"No microphone input available";
    return NO;
  }
  _targetFormat = [[AVAudioFormat alloc] initWithCommonFormat:AVAudioPCMFormatFloat32
                                                   sampleRate:kSampleRate
                                                     channels:1
                                                  interleaved:NO];
  _converter = [[AVAudioConverter alloc] initFromFormat:inputFormat toFormat:_targetFormat];

  [self resetSegmenter];
  __weak __typeof(self) weakSelf = self;
  [input installTapOnBus:0
              bufferSize:4096
                  format:inputFormat
                   block:^(AVAudioPCMBuffer *buffer, AVAudioTime *when) {
                     [weakSelf onInputBuffer:buffer];
                   }];
  [_audioEngine prepare];
  if (![_audioEngine startAndReturnError:&error]) {
    [input removeTapOnBus:0];
    if (errorMessage) *errorMessage = [NSString stringWithFormat:@"Audio engine: %@", error.localizedDescription];
    return NO;
  }
  _running = true;

  [NSNotificationCenter.defaultCenter addObserver:self
                                         selector:@selector(onInterruption:)
                                             name:AVAudioSessionInterruptionNotification
                                           object:session];
  return YES;
}

/// Runs on the real-time audio thread: convert to 16 kHz mono and hand off.
- (void)onInputBuffer:(AVAudioPCMBuffer *)buffer {
  if (!_running) return;
  double ratio = kSampleRate / buffer.format.sampleRate;
  AVAudioFrameCount capacity = (AVAudioFrameCount)(buffer.frameLength * ratio) + 32;
  AVAudioPCMBuffer *out = [[AVAudioPCMBuffer alloc] initWithPCMFormat:_targetFormat frameCapacity:capacity];

  __block BOOL consumed = NO;
  NSError *error = nil;
  [_converter convertToBuffer:out
                        error:&error
           withInputFromBlock:^AVAudioBuffer *(AVAudioPacketCount n, AVAudioConverterInputStatus *status) {
             if (consumed) {
               *status = AVAudioConverterInputStatus_NoDataNow;
               return nil;
             }
             consumed = YES;
             *status = AVAudioConverterInputStatus_HaveData;
             return buffer;
           }];
  if (error || out.frameLength == 0) return;

  std::vector<float> samples(out.floatChannelData[0], out.floatChannelData[0] + out.frameLength);
  dispatch_async(_audioQueue, ^{
    [self feed:samples];
  });
}

- (void)onInterruption:(NSNotification *)note {
  AVAudioSessionInterruptionType type =
      (AVAudioSessionInterruptionType)[note.userInfo[AVAudioSessionInterruptionTypeKey] unsignedIntegerValue];
  if (type == AVAudioSessionInterruptionTypeBegan) {
    _onError(@"Recording interrupted by the system (e.g. a phone call)");
  } else if (_running) {
    // Resume after the interruption ends (call declined/ended)
    NSError *error = nil;
    [AVAudioSession.sharedInstance setActive:YES error:&error];
    if (![_audioEngine startAndReturnError:&error]) {
      _onError([NSString stringWithFormat:@"Could not resume recording: %@", error.localizedDescription]);
    }
  }
}

- (void)stopWithCompletion:(dispatch_block_t)completion {
  if (!_running) {
    completion();
    return;
  }
  _running = false;
  [NSNotificationCenter.defaultCenter removeObserver:self name:AVAudioSessionInterruptionNotification object:nil];
  [_audioEngine.inputNode removeTapOnBus:0];
  [_audioEngine stop];
  _audioEngine = nil;
  [AVAudioSession.sharedInstance setActive:NO
                               withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
                                     error:nil];

  // Audio queue first (flush the trailing utterance), then drain the worker: both are serial
  dispatch_async(_audioQueue, ^{
    [self flush:YES];
    dispatch_async(self->_worker, ^{
      completion();
    });
  });
}

#pragma mark - Segmenter (_audioQueue)

- (int)chunkMs {
  return WSTVad.chunkSize * 1000 / kSampleRate;
}

- (void)resetSegmenter {
  dispatch_sync(_audioQueue, ^{
    self->_pending.clear();
    self->_preRoll.clear();
    self->_audio.clear();
    self->_active = self->_inSpeech = NO;
    self->_segmentId = 0;
    self->_carry.clear();
    [self->_vad reset];
  });
}

- (void)feed:(const std::vector<float> &)samples {
  _pending.insert(_pending.end(), samples.begin(), samples.end());
  const size_t chunk = WSTVad.chunkSize;
  size_t offset = 0;
  while (_pending.size() - offset >= chunk) {
    std::vector<float> frame(_pending.begin() + offset, _pending.begin() + offset + chunk);
    offset += chunk;
    [self push:frame prob:[_vad process:frame.data()]];
  }
  _pending.erase(_pending.begin(), _pending.begin() + offset);
}

- (void)push:(const std::vector<float> &)frame prob:(float)prob {
  const int chunkMs = [self chunkMs];
  if (!_active) {
    if (!_carry.empty() && ++_chunksSinceCarry * chunkMs > _config.carryWindowMs) {
      NSLog(@"[WhisperStt] Dropped %dms speech burst (nothing followed)", _carrySpeechMs);
      _carry.clear();
    }
    if (prob >= _config.speechThreshold) {
      _active = _inSpeech = YES;
      _segmentId++;
      _audio.clear();
      _speechMs = _trailingSilenceMs = 0;
      _speechEnd = 0;
      _submittedSpeechEnd = -1;
      _lastPartialAt = NowMs();
      if (!_carry.empty()) {
        NSLog(@"[WhisperStt] #%d starts with a carried %dms burst", _segmentId, _carrySpeechMs);
        _audio = _carry;
        _speechMs = _carrySpeechMs;
        _carry.clear();
      }
      for (const auto &f : _preRoll) _audio.insert(_audio.end(), f.begin(), f.end());
      _preRoll.clear();
    } else {
      _preRoll.push_back(frame);
      if ((int)_preRoll.size() > _config.preRollMs / chunkMs) _preRoll.pop_front();
      return;
    }
  }

  _audio.insert(_audio.end(), frame.begin(), frame.end());
  _inSpeech = _inSpeech ? prob >= _config.silenceThreshold : prob >= _config.speechThreshold;
  if (_inSpeech) {
    _speechMs += chunkMs;
    _trailingSilenceMs = 0;
    _speechEnd = _audio.size();
  } else {
    _trailingSilenceMs += chunkMs;
  }

  int utteranceMs = (int)(_audio.size() * 1000 / kSampleRate);
  BOOL ended = (_trailingSilenceMs >= _config.endSilenceMs && _speechMs >= _config.minUtteranceMs) ||
               _trailingSilenceMs >= _config.maxHoldSilenceMs || utteranceMs >= _config.maxUtteranceMs;
  if (ended) {
    [self flush:NO];
  } else if (_workerBusy) {
    return;
  } else if (!_inSpeech && _trailingSilenceMs >= _config.speculativeSilenceMs &&
             _speechMs >= _config.minSpeechMs && _submittedSpeechEnd != (long)_speechEnd) {
    // Speech probably ended: transcribe now instead of waiting out endSilenceMs
    [self submit:[self trimmedEnd] isFinal:NO];
  } else if (_config.livePartials && _inSpeech && !_engine.inBackground &&
             NowMs() - _lastPartialAt >= _config.partialIntervalMs) {
    // Partials only update the UI: skip them in the background, where runs are on the CPU
    [self submit:_audio.size() isFinal:NO];
  }
}

/// Keep ~300 ms of the trailing pause; Whisper doesn't need the rest
- (size_t)trimmedEnd {
  return MIN(_audio.size(), _speechEnd + (size_t)MIN(_trailingSilenceMs, 300) * kSampleRate / 1000);
}

- (void)flush:(BOOL)force {
  if (!_active) return;
  _active = NO;
  if (_speechMs < _config.minSpeechMs) {
    // Too short to transcribe alone. Keep it in case more speech follows; the UI drops any
    // partial it showed for this id
    if (!force) {
      _carry.assign(_audio.begin(), _audio.begin() + MAX((size_t)1, [self trimmedEnd]));
      _carrySpeechMs = _speechMs;
      _chunksSinceCarry = 0;
    }
    if (_config.livePartials) _onTranscript(_segmentId, @"", YES, 0, 0);
    return;
  }
  [self submit:[self trimmedEnd] isFinal:YES];
}

- (void)submit:(size_t)end isFinal:(BOOL)isFinal {
  _lastPartialAt = NowMs();
  _submittedSpeechEnd = (long)_speechEnd;
  end = MAX((size_t)1, MIN(end, _audio.size()));

  int segmentId = _segmentId;
  size_t speechEnd = _speechEnd;
  auto samples = std::make_shared<std::vector<float>>(_audio.begin(), _audio.begin() + end);
  double queuedAt = NowMs();
  _workerBusy = true;
  dispatch_async(_worker, ^{
    self->_workerBusy = true;
    // A partial that already covered every speech chunk is as good as a fresh run
    BOOL reuse = isFinal && self->_cachedId == segmentId && self->_cachedSpeechEnd >= speechEnd;
    NSString *text = reuse ? self->_cachedText : [self->_engine transcribe:samples->data()
                                                                               count:(int)samples->size()
                                                                              prompt:self->_config.prompt];
    self->_cachedId = segmentId;
    self->_cachedSpeechEnd = speechEnd;
    self->_cachedText = text;
    int audioMs = (int)(samples->size() * 1000 / kSampleRate);
    double latency = NowMs() - queuedAt;
    NSLog(@"[WhisperStt] #%d %@ audio=%dms latency=%.0fms: %@", segmentId,
          !isFinal ? @"partial" : (reuse ? @"final(reused)" : @"final"), audioMs, latency, text);
    self->_onTranscript(segmentId, text, isFinal, audioMs, latency);
    self->_workerBusy = false;
  });
}

@end
