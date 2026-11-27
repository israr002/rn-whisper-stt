#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// whisper.cpp model wrapper. Not thread-safe: use from one serial queue.
@interface WSTEngine : NSObject

@property (nonatomic, readonly) BOOL isLoaded;

/// The app is in the background. iOS forbids GPU work there, so runs use a CPU-only context;
/// callers can skip work nobody sees (live partials). Safe to read from any thread.
@property (nonatomic, readonly) BOOL inBackground;

/// Compiles ggml's Metal shaders (and registers its backends) ahead of the first model load. The
/// first compile after an install takes seconds (iPhone 13 Pro: ~8 s); iOS caches it after that.
/// Blocking: call on a background queue.
+ (void)prepareBackends;

/// Loads a ggml model (absolute path) and warms it up. Uses Metal in the foreground and a
/// lazily loaded CPU context in the background.
- (BOOL)loadModelAtPath:(NSString *)path;

/// 16 kHz mono PCM in [-1, 1]; at most 30 s is used. `prompt`: vocabulary hint, treated as
/// text that came before this audio.
- (NSString *)transcribe:(const float *)samples count:(int)count prompt:(nullable NSString *)prompt;

- (void)unload;

@end

NS_ASSUME_NONNULL_END
