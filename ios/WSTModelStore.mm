#import "WSTModelStore.h"

static BOOL IsGgml(NSURL *url);

@interface WSTDownloadTask : NSObject
@property (nonatomic, copy) NSString *fileName;
@property (nonatomic, copy) WSTDownloadProgress progress;
@property (nonatomic, copy) WSTDownloadCompletion completion;
@property (nonatomic) CFTimeInterval lastReport;
@end

@implementation WSTDownloadTask
@end

@interface WSTModelStore () <NSURLSessionDownloadDelegate>
@end

@implementation WSTModelStore {
  NSURLSession *_session;
  NSMutableDictionary<NSNumber *, WSTDownloadTask *> *_tasks;
}

- (instancetype)init {
  if ((self = [super init])) {
    NSOperationQueue *queue = [NSOperationQueue new];
    queue.maxConcurrentOperationCount = 1;
    _session = [NSURLSession sessionWithConfiguration:NSURLSessionConfiguration.defaultSessionConfiguration
                                             delegate:self
                                        delegateQueue:queue];
    _tasks = [NSMutableDictionary new];
  }
  return self;
}

- (NSURL *)directory {
  NSURL *support = [NSFileManager.defaultManager URLsForDirectory:NSApplicationSupportDirectory
                                                        inDomains:NSUserDomainMask].firstObject;
  return [support URLByAppendingPathComponent:@"whisper-models" isDirectory:YES];
}

- (nullable NSURL *)urlForFileName:(NSString *)fileName {
  if (fileName.length == 0 || [fileName containsString:@"/"] || [fileName hasPrefix:@"."]) return nil;
  return [[self directory] URLByAppendingPathComponent:fileName];
}

- (nullable NSString *)pathForFileName:(NSString *)fileName {
  NSURL *url = [self urlForFileName:fileName];
  return url && IsGgml(url) ? url.path : nil;
}

- (void)deleteFileName:(NSString *)fileName {
  NSURL *url = [self urlForFileName:fileName];
  if (url) [NSFileManager.defaultManager removeItemAtURL:url error:nil];
}

- (void)download:(NSString *)url
        fileName:(NSString *)fileName
        progress:(WSTDownloadProgress)progress
      completion:(WSTDownloadCompletion)completion {
  NSURL *remote = [NSURL URLWithString:url];
  if (!remote || ![self urlForFileName:fileName]) {
    completion(nil, [NSString stringWithFormat:@"Invalid model URL or file name: %@", fileName]);
    return;
  }
  NSURLSessionDownloadTask *task = [_session downloadTaskWithURL:remote];
  WSTDownloadTask *entry = [WSTDownloadTask new];
  entry.fileName = fileName;
  entry.progress = progress;
  entry.completion = completion;
  @synchronized(_tasks) {
    _tasks[@(task.taskIdentifier)] = entry;
  }
  [task resume];
}

- (nullable WSTDownloadTask *)entryFor:(NSURLSessionTask *)task remove:(BOOL)remove {
  @synchronized(_tasks) {
    WSTDownloadTask *entry = _tasks[@(task.taskIdentifier)];
    if (remove) [_tasks removeObjectForKey:@(task.taskIdentifier)];
    return entry;
  }
}

#pragma mark - NSURLSessionDownloadDelegate

- (void)URLSession:(NSURLSession *)session
                 downloadTask:(NSURLSessionDownloadTask *)downloadTask
                 didWriteData:(int64_t)bytesWritten
            totalBytesWritten:(int64_t)totalBytesWritten
    totalBytesExpectedToWrite:(int64_t)totalBytesExpectedToWrite {
  WSTDownloadTask *entry = [self entryFor:downloadTask remove:NO];
  CFTimeInterval now = CACurrentMediaTime();
  if (entry && now - entry.lastReport >= 0.1) { // ~10 updates/s
    entry.lastReport = now;
    entry.progress(totalBytesWritten, totalBytesExpectedToWrite);
  }
}

- (void)URLSession:(NSURLSession *)session
                 downloadTask:(NSURLSessionDownloadTask *)downloadTask
    didFinishDownloadingToURL:(NSURL *)location {
  WSTDownloadTask *entry = [self entryFor:downloadTask remove:YES];
  if (!entry) return;

  NSInteger status = [(NSHTTPURLResponse *)downloadTask.response statusCode];
  int64_t expected = downloadTask.countOfBytesExpectedToReceive;
  int64_t received = downloadTask.countOfBytesReceived;
  NSString *error = nil;
  if (status != 200) {
    error = [NSString stringWithFormat:@"HTTP %ld downloading model", (long)status];
  } else if (expected > 0 && received != expected) {
    error = [NSString stringWithFormat:@"Download incomplete: %lld of %lld bytes", received, expected];
  } else if (!IsGgml(location)) {
    error = @"Downloaded file is not a ggml model";
  }
  if (error) {
    entry.completion(nil, error);
    return;
  }

  // The temp file is deleted when this method returns: move it into place now
  NSFileManager *fm = NSFileManager.defaultManager;
  NSURL *target = [self urlForFileName:entry.fileName];
  NSError *fsError = nil;
  [fm createDirectoryAtURL:[self directory] withIntermediateDirectories:YES attributes:nil error:nil];
  [fm removeItemAtURL:target error:nil];
  if (![fm moveItemAtURL:location toURL:target error:&fsError]) {
    entry.completion(nil, [NSString stringWithFormat:@"Could not move model into place: %@", fsError.localizedDescription]);
    return;
  }
  // Re-downloadable, and large: keep it out of iCloud/device backups
  [target setResourceValue:@YES forKey:NSURLIsExcludedFromBackupKey error:nil];
  entry.progress(received, expected > 0 ? expected : received);
  entry.completion(target.path, nil);
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
  if (!error) return; // success is handled in didFinishDownloadingToURL
  WSTDownloadTask *entry = [self entryFor:task remove:YES];
  if (entry) entry.completion(nil, error.localizedDescription);
}

// whisper.cpp ggml files start with the magic 0x67676d6c, stored little-endian ("lmgg")
static BOOL IsGgml(NSURL *url) {
  NSFileHandle *handle = [NSFileHandle fileHandleForReadingFromURL:url error:nil];
  NSData *head = [handle readDataOfLength:4];
  [handle closeFile];
  return head.length == 4 && memcmp(head.bytes, "lmgg", 4) == 0;
}

@end
