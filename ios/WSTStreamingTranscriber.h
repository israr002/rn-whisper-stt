#import <Foundation/Foundation.h>

#import "WSTEngine.h"

NS_ASSUME_NONNULL_BEGIN

/// Same knobs and defaults as Android's StreamingTranscriber.Config
@interface WSTStreamingConfig : NSObject
@property (nonatomic) float speechThreshold;    // 0.45: Silero's 0.5 missed quiet far-field words
@property (nonatomic) float silenceThreshold;   // 0.3: hysteresis, below this counts as silence
@property (nonatomic) int endSilenceMs;         // 700: pause that ends an utterance
@property (nonatomic) int minUtteranceMs;       // 1000: shorter utterances wait to merge with the next...
@property (nonatomic) int maxHoldSilenceMs;     // 2000: ...unless the pause is this long
@property (nonatomic) int minSpeechMs;          // 250: below this it's not transcribed on its own...
@property (nonatomic) int carryWindowMs;        // 2000: ...but joins the next utterance if that starts this soon
@property (nonatomic) int maxUtteranceMs;       // 20000: force a cut; Whisper's window is 30 s
@property (nonatomic) int preRollMs;            // 800: audio kept from before speech onset
@property (nonatomic) BOOL livePartials;        // YES
@property (nonatomic) int partialIntervalMs;    // 600
@property (nonatomic) int speculativeSilenceMs; // 200: transcribe this soon after speech stops
@property (nonatomic, copy, nullable) NSString *prompt; // vocabulary hint passed to every run
@end

typedef void (^WSTTranscriptHandler)(int segmentId, NSString *text, BOOL isFinal, int audioMs, double latencyMs);
typedef void (^WSTErrorHandler)(NSString *message);

/// Continuous transcription: mic -> Silero VAD -> utterance segments -> whisper.cpp.
/// Whisper runs on whole utterances cut at pauses; partials re-transcribe the utterance in progress
/// whenever the worker is idle, and the final replaces them.
@interface WSTStreamingTranscriber : NSObject

/// worker must be a serial queue shared with model loading, so jobs run strictly in order.
- (instancetype)initWithEngine:(WSTEngine *)engine
                        worker:(dispatch_queue_t)worker
                  vadModelPath:(NSString *)vadModelPath
                        config:(WSTStreamingConfig *)config
                  onTranscript:(WSTTranscriptHandler)onTranscript
                       onError:(WSTErrorHandler)onError;

/// Configures the audio session and starts capture. Returns NO with an error message on failure.
- (BOOL)start:(NSString *_Nullable *_Nullable)errorMessage;

/// Stops capture, transcribes the trailing utterance and calls completion after all pending results.
- (void)stopWithCompletion:(dispatch_block_t)completion;

@end

NS_ASSUME_NONNULL_END
