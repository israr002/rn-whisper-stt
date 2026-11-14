#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Silero VAD v5 via whisper.cpp's ggml port. Feed consecutive 512-sample (32 ms, 16 kHz) chunks.
/// Not thread-safe: use from one serial queue.
@interface WSTVad : NSObject

+ (int)chunkSize;

- (nullable instancetype)initWithModelPath:(NSString *)path;

/// Speech probability of the next chunk; keeps the model's state across calls.
- (float)process:(const float *)chunk;

- (void)reset;

@end

NS_ASSUME_NONNULL_END
