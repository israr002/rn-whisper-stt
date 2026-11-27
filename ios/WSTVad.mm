#import "WSTVad.h"

#import <whisper.h>

@implementation WSTVad {
  struct whisper_vad_context *_ctx;
}

+ (int)chunkSize {
  return 512;
}

- (nullable instancetype)initWithModelPath:(NSString *)path {
  if ((self = [super init])) {
    struct whisper_vad_context_params params = whisper_vad_default_context_params();
    params.n_threads = 1;
    params.use_gpu = false; // tiny model: the CPU is faster than a GPU round trip per 32 ms chunk
    _ctx = whisper_vad_init_from_file_with_params(path.fileSystemRepresentation, params);
    if (!_ctx) return nil;
  }
  return self;
}

- (float)process:(const float *)chunk {
  // _no_reset keeps the LSTM state between chunks; that's what makes it usable for streaming
  if (!whisper_vad_detect_speech_no_reset(_ctx, chunk, WSTVad.chunkSize)) return 0.0f;
  return whisper_vad_n_probs(_ctx) > 0 ? whisper_vad_probs(_ctx)[0] : 0.0f;
}

- (void)reset {
  whisper_vad_reset_state(_ctx);
}

- (void)dealloc {
  if (_ctx) whisper_vad_free(_ctx);
}

@end
