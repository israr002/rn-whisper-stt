#import "WhisperStt.h"

// iOS is not implemented yet: every call rejects with E_UNSUPPORTED so apps fail clearly.
static NSString *const kUnsupported = @"react-native-whisper-stt: iOS is not supported yet.";

@implementation WhisperStt

- (void)downloadModel:(NSString *)url
             fileName:(NSString *)fileName
              resolve:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject
{
  reject(@"E_UNSUPPORTED", kUnsupported, nil);
}

- (void)getModelPath:(NSString *)fileName
             resolve:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject
{
  resolve([NSNull null]);
}

- (void)deleteModel:(NSString *)fileName
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject
{
  resolve(nil);
}

- (void)loadModel:(NSString *)modelPath
          resolve:(RCTPromiseResolveBlock)resolve
           reject:(RCTPromiseRejectBlock)reject
{
  reject(@"E_UNSUPPORTED", kUnsupported, nil);
}

- (NSNumber *)isInitialized
{
  return @NO;
}

- (void)releaseModel:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  resolve(nil);
}

- (void)startRecording:(JS::NativeWhisperStt::SpecStartRecordingOptions &)options
               resolve:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject
{
  reject(@"E_UNSUPPORTED", kUnsupported, nil);
}

- (void)stopRecording:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  resolve(@"");
}

- (NSNumber *)isRecording
{
  return @NO;
}

- (void)addListener:(NSString *)eventName {}

- (void)removeListeners:(double)count {}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeWhisperSttSpecJSI>(params);
}

+ (NSString *)moduleName
{
  return @"WhisperStt";
}

@end
