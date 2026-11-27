#import "WhisperStt.h"

#import <AVFoundation/AVFoundation.h>

#import "WSTEngine.h"
#import "WSTModelStore.h"
#import "WSTStreamingTranscriber.h"

static NSString *const kEventTranscript = @"WhisperSttTranscript";
static NSString *const kEventError = @"WhisperSttError";
static NSString *const kEventDownloadProgress = @"WhisperSttDownloadProgress";

@implementation WhisperStt {
  WSTEngine *_engine;
  WSTModelStore *_models;
  // Serial: model loading, then every transcription job, strictly in order
  dispatch_queue_t _worker;
  WSTStreamingTranscriber *_transcriber;
  NSMutableDictionary<NSNumber *, NSString *> *_finalSegments;
  BOOL _hasListeners;
}

RCT_EXPORT_MODULE()

- (instancetype)init {
  if ((self = [super init])) {
    _engine = [WSTEngine new];
    _models = [WSTModelStore new];
    _worker = dispatch_queue_create("WhisperWorker", dispatch_queue_attr_make_with_qos_class(
                                                       DISPATCH_QUEUE_SERIAL, QOS_CLASS_USER_INITIATED, 0));
    _finalSegments = [NSMutableDictionary new];
    // Overlaps the one-time Metal shader compile with the model download; loadModel queues behind it
    dispatch_async(_worker, ^{
      [WSTEngine prepareBackends];
    });
  }
  return self;
}

+ (BOOL)requiresMainQueueSetup {
  return NO;
}

- (NSArray<NSString *> *)supportedEvents {
  return @[ kEventTranscript, kEventError, kEventDownloadProgress ];
}

- (void)startObserving {
  _hasListeners = YES;
}

- (void)stopObserving {
  _hasListeners = NO;
}

- (void)emit:(NSString *)name body:(NSDictionary *)body {
  if (_hasListeners) [self sendEventWithName:name body:body];
}

#pragma mark - Models

- (void)downloadModel:(NSString *)url
             fileName:(NSString *)fileName
              resolve:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject {
  NSString *existing = [_models pathForFileName:fileName];
  if (existing) {
    resolve(existing);
    return;
  }
  __weak __typeof(self) weakSelf = self;
  [_models download:url
      fileName:fileName
      progress:^(int64_t written, int64_t total) {
        [weakSelf emit:kEventDownloadProgress
                  body:@{
                    @"fileName" : fileName,
                    @"bytesWritten" : @(written),
                    @"totalBytes" : @(total),
                    @"progress" : @(total > 0 ? (double)written / total : -1),
                  }];
      }
      completion:^(NSString *path, NSString *error) {
        if (path) {
          resolve(path);
        } else {
          reject(@"E_DOWNLOAD", error ?: @"Download failed", nil);
        }
      }];
}

- (void)getModelPath:(NSString *)fileName resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  resolve([_models pathForFileName:fileName] ?: (id)[NSNull null]);
}

- (void)deleteModel:(NSString *)fileName resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  [_models deleteFileName:fileName];
  resolve(nil);
}

#pragma mark - Engine

- (void)loadModel:(NSString *)modelPath resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  if (_transcriber) {
    reject(@"E_ALREADY_RECORDING", @"Stop recording before loading a model", nil);
    return;
  }
  dispatch_async(_worker, ^{
    if ([self->_engine loadModelAtPath:modelPath]) {
      resolve(nil);
    } else {
      reject(@"E_MODEL", [NSString stringWithFormat:@"Failed to load model at %@", modelPath], nil);
    }
  });
}

- (NSNumber *)isInitialized {
  return @(_engine.isLoaded);
}

- (void)releaseModel:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  if (_transcriber) {
    reject(@"E_ALREADY_RECORDING", @"Stop recording before releasing the model", nil);
    return;
  }
  dispatch_async(_worker, ^{
    [self->_engine unload];
    resolve(nil);
  });
}

#pragma mark - Recording

- (nullable NSString *)vadModelPath {
  NSURL *bundleURL = [[NSBundle bundleForClass:WhisperStt.class] URLForResource:@"WhisperStt" withExtension:@"bundle"];
  return [[NSBundle bundleWithURL:bundleURL] pathForResource:@"ggml-silero-v5.1.2" ofType:@"bin"];
}

- (void)startRecording:(JS::NativeWhisperStt::SpecStartRecordingOptions &)options
               resolve:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject {
  if (!_engine.isLoaded) {
    reject(@"E_NOT_INITIALIZED", @"Call init() before startRecording()", nil);
    return;
  }
  if (_transcriber) {
    reject(@"E_ALREADY_RECORDING", @"Already recording", nil);
    return;
  }
  if (AVAudioSession.sharedInstance.recordPermission != AVAudioSessionRecordPermissionGranted) {
    reject(@"E_PERMISSION", @"Microphone permission not granted", nil);
    return;
  }
  NSString *vadPath = [self vadModelPath];
  if (!vadPath) {
    reject(@"E_MODEL", @"VAD model missing from the WhisperStt resource bundle", nil);
    return;
  }

  WSTStreamingConfig *config = [WSTStreamingConfig new];
  if (options.speechThreshold()) config.speechThreshold = *options.speechThreshold();
  if (options.silenceThreshold()) config.silenceThreshold = *options.silenceThreshold();
  if (options.endSilenceMs()) config.endSilenceMs = (int)*options.endSilenceMs();
  if (options.minUtteranceMs()) config.minUtteranceMs = (int)*options.minUtteranceMs();
  if (options.maxUtteranceMs()) config.maxUtteranceMs = MIN(29000, (int)*options.maxUtteranceMs());
  if (options.preRollMs()) config.preRollMs = (int)*options.preRollMs();
  if (options.livePartials()) config.livePartials = *options.livePartials();
  if (options.partialIntervalMs()) config.partialIntervalMs = (int)*options.partialIntervalMs();
  NSString *prompt = [options.prompt() stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
  if (prompt.length) config.prompt = prompt;
  // foregroundService / notification*: Android only. On iOS, background recording needs the app's
  // UIBackgroundModes to include "audio".

  @synchronized(_finalSegments) {
    [_finalSegments removeAllObjects];
  }
  __weak __typeof(self) weakSelf = self;
  WSTStreamingTranscriber *transcriber = [[WSTStreamingTranscriber alloc]
      initWithEngine:_engine
              worker:_worker
        vadModelPath:vadPath
              config:config
        onTranscript:^(int segmentId, NSString *text, BOOL isFinal, int audioMs, double latencyMs) {
          [weakSelf onTranscript:segmentId text:text isFinal:isFinal audioMs:audioMs latencyMs:latencyMs];
        }
             onError:^(NSString *message) {
               [weakSelf emit:kEventError body:@{@"code" : @"E_RECORDING", @"message" : message}];
             }];

  NSString *error = nil;
  if (![transcriber start:&error]) {
    reject(@"E_RECORDING", error ?: @"Could not start recording", nil);
    return;
  }
  _transcriber = transcriber;
  resolve(nil);
}

- (void)onTranscript:(int)segmentId text:(NSString *)text isFinal:(BOOL)isFinal audioMs:(int)audioMs latencyMs:(double)latencyMs {
  if (isFinal) {
    @synchronized(_finalSegments) {
      _finalSegments[@(segmentId)] = text;
    }
  }
  [self emit:kEventTranscript
        body:@{
          @"id" : @(segmentId),
          @"text" : text,
          @"isFinal" : @(isFinal),
          @"audioMs" : @(audioMs),
          @"latencyMs" : @(latencyMs),
        }];
}

- (void)stopRecording:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  WSTStreamingTranscriber *transcriber = _transcriber;
  if (!transcriber) {
    resolve(@"");
    return;
  }
  _transcriber = nil;
  [transcriber stopWithCompletion:^{
    NSMutableArray<NSString *> *parts = [NSMutableArray new];
    @synchronized(self->_finalSegments) {
      for (NSNumber *key in [self->_finalSegments.allKeys sortedArrayUsingSelector:@selector(compare:)]) {
        NSString *text = self->_finalSegments[key];
        if (text.length) [parts addObject:text];
      }
    }
    resolve([parts componentsJoinedByString:@" "]);
  }];
}

- (NSNumber *)isRecording {
  return @(_transcriber != nil);
}

- (void)requestMicrophonePermission:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  [AVAudioSession.sharedInstance requestRecordPermission:^(BOOL granted) {
    resolve(@(granted));
  }];
}

// addListener / removeListeners come from RCTEventEmitter

- (void)invalidate {
  [_transcriber stopWithCompletion:^{}];
  _transcriber = nil;
  WSTEngine *engine = _engine;
  dispatch_async(_worker, ^{
    [engine unload];
  });
  [super invalidate];
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params {
  return std::make_shared<facebook::react::NativeWhisperSttSpecJSI>(params);
}

@end
