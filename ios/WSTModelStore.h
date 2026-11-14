#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^WSTDownloadProgress)(int64_t written, int64_t total);
typedef void (^WSTDownloadCompletion)(NSString *_Nullable path, NSString *_Nullable error);

/// Downloads ggml models into Application Support/whisper-models and finds downloaded ones.
@interface WSTModelStore : NSObject

/// Path of a complete, valid model, or nil.
- (nullable NSString *)pathForFileName:(NSString *)fileName;

- (void)deleteFileName:(NSString *)fileName;

- (void)download:(NSString *)url
        fileName:(NSString *)fileName
        progress:(WSTDownloadProgress)progress
      completion:(WSTDownloadCompletion)completion;

@end

NS_ASSUME_NONNULL_END
