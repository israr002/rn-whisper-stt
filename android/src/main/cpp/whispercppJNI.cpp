#include <jni.h>
#include <string>
#include <android/log.h>

#include "whisper.h"

#define TAG "WhisperCpp"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_whisperstt_whisperengine_WhisperCppEngine_nativeInit(JNIEnv *env, jobject thiz, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!ctx) __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to load model");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_whisperstt_whisperengine_WhisperCppEngine_nativeFree(JNIEnv *env, jobject thiz, jlong ptr) {
    whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

// Returns UTF-8 bytes: NewStringUTF aborts on the partial multibyte tokens whisper can emit
JNIEXPORT jbyteArray JNICALL
Java_com_whisperstt_whisperengine_WhisperCppEngine_nativeTranscribe(JNIEnv *env, jobject thiz, jlong ptr,
                                                                    jfloatArray samples, jint nThreads,
                                                                    jint audioCtx, jint maxTokens) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = nThreads;
    params.language = "en";
    params.translate = false;
    params.no_context = true;        // segments are independent utterances
    params.no_timestamps = true;
    params.single_segment = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;      // no "(music)", "[noise]"-style tokens on background noise
    params.audio_ctx = audioCtx;     // 0 = full 30s window
    // On repetitive/low-confidence output (entropy_thold, logprob_thold) re-decode at a higher
    // temperature. Only the decoder re-runs, so it's much cheaper than re-encoding with a wider window
    params.temperature_inc = 0.4f;   // at most 2 fallbacks (0.4, 0.8): bounds the worst case
    params.greedy.best_of = 1;
    params.max_tokens = maxTokens;   // bounds the cost of a repetition loop

    jsize n = env->GetArrayLength(samples);
    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    int rc = whisper_full(ctx, params, pcm, n);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed: %d", rc);
        return env->NewByteArray(0);
    }

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) text += whisper_full_get_segment_text(ctx, i);
    jbyteArray out = env->NewByteArray((jsize) text.size());
    env->SetByteArrayRegion(out, 0, (jsize) text.size(), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

} // extern "C"
