#import "WSTEngine.h"

#import <UIKit/UIKit.h>
#import <ggml-backend.h>
#import <sys/sysctl.h>
#import <whisper.h>

#include <atomic>
#include <map>
#include <regex>
#include <string>
#include <vector>

static const int kSampleRate = 16000;
static const int kMaxAudioCtx = 1500; // 30 s
// Extra encoder positions beyond the audio; too tight a window makes whisper loop or drop words.
// Same on Metal and CPU. A wider window (256) is nearly free on Metal and loops less, but it
// changed the text: on identical iPhone 13 Pro captures it dropped leading words ("ask what you
// can do" -> "what you can do") on CPU and GPU alike. Loops are caught and retried instead.
static const int kAudioCtxPadding = 64;
// Short utterances loop most often with a tight window; never go below this (256 = 5 s)
static const int kMinAudioCtx = 256;

// whisper.cpp logs every VAD chunk at INFO level; keep only warnings and errors
static void WSTLog(enum ggml_log_level level, const char *text, void *) {
  if (level >= GGML_LOG_LEVEL_WARN) fprintf(stderr, "%s", text);
}

/// Performance cores only: ggml's thread pool waits on its slowest thread
static int WSTThreadCount(void) {
  int pcores = 0;
  size_t size = sizeof(pcores);
  if (sysctlbyname("hw.perflevel0.physicalcpu", &pcores, &size, NULL, 0) != 0 || pcores <= 0) {
    pcores = (int)NSProcessInfo.processInfo.activeProcessorCount / 2;
  }
  return MAX(1, MIN(4, pcores));
}

static bool LooksLikeLoop(const std::string &text, int nSamples);
static NSString *Trimmed(const std::string &text);

static struct whisper_context *LoadContext(NSString *path, bool gpu) {
  struct whisper_context_params cparams = whisper_context_default_params();
  cparams.use_gpu = gpu;
  // No flash attention: with it, Metal output differed from the CPU's on 1 of 8 captured segments
  // (4 of 8 combined with a wide audio_ctx); without it the two matched on all 8, for ~25% more time
  cparams.flash_attn = false;
  CFAbsoluteTime t = CFAbsoluteTimeGetCurrent();
  struct whisper_context *ctx = whisper_init_from_file_with_params(path.fileSystemRepresentation, cparams);
  NSLog(@"[WhisperStt] loaded %s context in %.0fms", gpu ? "Metal" : "CPU", (CFAbsoluteTimeGetCurrent() - t) * 1000);
  return ctx;
}

/// iOS refuses GPU work from background apps (kIOGPUCommandBufferCallbackErrorBackgroundExecutionNotPermitted),
/// and a Metal backend that hit that error stays broken until it is recreated. So: Metal in the foreground,
/// a CPU context in the background (loaded on first need), and a failed Metal run is redone on the CPU,
/// with the Metal context rebuilt after the app returns to the foreground.
@implementation WSTEngine {
  NSString *_path;
  struct whisper_context *_gpuCtx;
  struct whisper_context *_cpuCtx;
  std::atomic<bool> _background;
  // Set when a Metal run fails; cleared on returning to the foreground
  std::atomic<bool> _gpuFailed;
  id _backgroundObserver;
  id _foregroundObserver;
}

+ (void)initialize {
  if (self == [WSTEngine class]) whisper_log_set(WSTLog, NULL);
}

+ (void)prepareBackends {
  // ggml builds its backend registry once per process, and creating the Metal device compiles the
  // shader library from source. Any registry query triggers it, even for CPU-only use.
  CFAbsoluteTime t = CFAbsoluteTimeGetCurrent();
  size_t devices = ggml_backend_dev_count();
  NSLog(@"[WhisperStt] backends ready in %.0fms (%zu devices)", (CFAbsoluteTimeGetCurrent() - t) * 1000, devices);
}

- (instancetype)init {
  if ((self = [super init])) {
    __weak WSTEngine *weakSelf = self;
    NSNotificationCenter *center = NSNotificationCenter.defaultCenter;
    _backgroundObserver = [center addObserverForName:UIApplicationDidEnterBackgroundNotification
                                              object:nil
                                               queue:nil
                                          usingBlock:^(NSNotification *note) {
                                            WSTEngine *strongSelf = weakSelf;
                                            if (strongSelf) strongSelf->_background = true;
                                          }];
    _foregroundObserver = [center addObserverForName:UIApplicationWillEnterForegroundNotification
                                              object:nil
                                               queue:nil
                                          usingBlock:^(NSNotification *note) {
                                            WSTEngine *strongSelf = weakSelf;
                                            if (!strongSelf) return;
                                            strongSelf->_background = false;
                                            strongSelf->_gpuFailed = false;
                                          }];
    dispatch_async(dispatch_get_main_queue(), ^{
      WSTEngine *strongSelf = weakSelf;
      if (strongSelf) {
        strongSelf->_background = UIApplication.sharedApplication.applicationState == UIApplicationStateBackground;
      }
    });
  }
  return self;
}

- (BOOL)isLoaded {
  return _path != nil;
}

- (BOOL)inBackground {
  return _background;
}

- (BOOL)loadModelAtPath:(NSString *)path {
  [self unload];
  if (![NSFileManager.defaultManager fileExistsAtPath:path]) return NO;
  _path = [path copy];

  bool gpu = false;
  if (![self contextUsingGpu:&gpu]) {
    [self unload];
    return NO;
  }
  // Warm-up: the first run compiles Metal pipelines and allocates compute buffers
  CFAbsoluteTime t = CFAbsoluteTimeGetCurrent();
  std::vector<float> silence(kSampleRate, 0.0f);
  [self transcribe:silence.data() count:(int)silence.size() prompt:nil];
  NSLog(@"[WhisperStt] warmup=%.0fms threads=%d", (CFAbsoluteTimeGetCurrent() - t) * 1000, WSTThreadCount());
  return YES;
}

/// The context for the next run, loading it if needed
- (struct whisper_context *)contextUsingGpu:(bool *)gpu {
#if !TARGET_OS_SIMULATOR // the simulator's Metal driver can't create ggml's shared buffers
  if (!_background && !_gpuFailed) {
    if (!_gpuCtx) _gpuCtx = LoadContext(_path, true);
    if (_gpuCtx) {
      // Back in the foreground: give the background context's memory back
      if (_cpuCtx) {
        whisper_free(_cpuCtx);
        _cpuCtx = NULL;
      }
      *gpu = true;
      return _gpuCtx;
    }
  }
#endif
  if (!_cpuCtx) _cpuCtx = LoadContext(_path, false);
  *gpu = false;
  return _cpuCtx;
}

- (void)unload {
  if (_gpuCtx) whisper_free(_gpuCtx);
  if (_cpuCtx) whisper_free(_cpuCtx);
  _gpuCtx = NULL;
  _cpuCtx = NULL;
  _path = nil;
  _gpuFailed = false;
}

- (void)dealloc {
  [NSNotificationCenter.defaultCenter removeObserver:_backgroundObserver];
  [NSNotificationCenter.defaultCenter removeObserver:_foregroundObserver];
  [self unload];
}

- (NSString *)transcribe:(const float *)samples count:(int)count prompt:(nullable NSString *)prompt {
  if (!_path) return @"";
  bool gpu = false;
  struct whisper_context *ctx = [self contextUsingGpu:&gpu];
  if (!ctx) return @"";

  // A reduced audio_ctx occasionally sends the decoder into a repetition loop; retry with
  // progressively wider windows, then the full one. Encoder positions: one per 320 samples
  const int padding = kAudioCtxPadding;
  for (int audioCtx : {MAX(kMinAudioCtx, MIN(kMaxAudioCtx, count / 320 + padding)),
                       MAX(kMinAudioCtx, MIN(kMaxAudioCtx, count / 320 + padding * 4)), 0}) {
    std::string text;
    if (![self run:ctx gpu:gpu samples:samples count:count audioCtx:audioCtx prompt:prompt text:&text]) {
      if (!gpu) return @"";
      // Most likely the app just went to the background: drop the broken Metal context, redo on the CPU
      NSLog(@"[WhisperStt] Metal run failed, falling back to the CPU");
      _gpuFailed = true;
      whisper_free(_gpuCtx);
      _gpuCtx = NULL;
      return [self transcribe:samples count:count prompt:prompt];
    }
    if (audioCtx == 0 || !LooksLikeLoop(text, count)) return Trimmed(text);
    NSLog(@"[WhisperStt] repetition with audio_ctx=%d, retrying: %s", audioCtx, text.c_str());
  }
  return @"";
}

- (BOOL)run:(struct whisper_context *)ctx
         gpu:(bool)gpu
     samples:(const float *)samples
       count:(int)count
    audioCtx:(int)audioCtx
      prompt:(nullable NSString *)prompt
        text:(std::string *)text {
  CFAbsoluteTime t0 = CFAbsoluteTimeGetCurrent();
  int threads = WSTThreadCount();

  struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
  params.n_threads = threads;
  params.language = "en";
  params.translate = false;
  params.no_context = true; // segments are independent utterances
  params.no_timestamps = true;
  params.single_segment = true;
  params.print_progress = false;
  params.print_realtime = false;
  params.print_special = false;
  params.print_timestamps = false;
  params.suppress_blank = true;
  params.suppress_nst = true; // no "(music)", "[noise]"-style tokens on background noise
  params.audio_ctx = audioCtx; // 0 = full 30 s window
  // On repetitive/low-confidence output re-decode at a higher temperature; at most 2 fallbacks
  params.temperature_inc = 0.4f;
  params.greedy.best_of = 1;
  // ~3 words/s of speech is ~4 tokens/s; 6/s plus slack leaves room for fast talkers
  params.max_tokens = 16 + count * 6 / kSampleRate;
  // Vocabulary hint; whisper.cpp adds it after no_context clears the history
  if (prompt.length) params.initial_prompt = prompt.UTF8String;

  if (whisper_full(ctx, params, samples, count) != 0) {
    NSLog(@"[WhisperStt] whisper_full failed (%s)", gpu ? "Metal" : "CPU");
    return NO;
  }
  text->clear();
  for (int i = 0; i < whisper_full_n_segments(ctx); i++) *text += whisper_full_get_segment_text(ctx, i);

  NSLog(@"[WhisperStt] audio=%dms total=%.0fms audio_ctx=%d %s threads=%d", count / 16,
        (CFAbsoluteTimeGetCurrent() - t0) * 1000, audioCtx, gpu ? "metal" : "cpu", threads);
  return YES;
}

// Too many words for the audio length, a 3+ word phrase repeated back to back,
// or a 3+ word phrase repeated 3+ times anywhere
static bool LooksLikeLoop(const std::string &text, int nSamples) {
  std::vector<std::string> words;
  std::string lower;
  for (char c : text) lower += (char)tolower((unsigned char)c);
  std::regex wordRe("[a-z0-9']+");
  for (auto it = std::sregex_iterator(lower.begin(), lower.end(), wordRe); it != std::sregex_iterator(); ++it) {
    words.push_back(it->str());
  }
  const int n = (int)words.size();
  if (n > 6 + nSamples * 5 / kSampleRate) return true;

  for (int len = 3; len <= n / 2; len++) {
    for (int i = 0; i + 2 * len <= n; i++) {
      if (std::equal(words.begin() + i, words.begin() + i + len, words.begin() + i + len)) return true;
    }
  }
  for (int len = 3; len <= 6; len++) {
    std::map<std::vector<std::string>, int> counts;
    for (int i = 0; i + len <= n; i++) {
      if (++counts[std::vector<std::string>(words.begin() + i, words.begin() + i + len)] >= 3) return true;
    }
  }
  return false;
}

static NSString *Trimmed(const std::string &text) {
  // whisper can end on a multibyte character split across tokens: drop up to 3 trailing bytes
  NSString *s = nil;
  for (size_t cut = 0; !s && cut <= 3 && cut <= text.size(); cut++) {
    s = [[NSString alloc] initWithBytes:text.data() length:text.size() - cut encoding:NSUTF8StringEncoding];
  }
  return [s ?: @"" stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
}

@end
