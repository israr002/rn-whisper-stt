#include <jni.h>
#include <vector>
#include "whisperstt.h"
#include <android/log.h>

extern "C" {

// JNI method to create an instance of Engine
JNIEXPORT jlong JNICALL
Java_com_whisperstt_whisperengine_WhisperEngine_createEngine(JNIEnv *env,
                                                             jobject thiz) {
    __android_log_print(ANDROID_LOG_INFO, "TRACKERS_init", "%s", "start");
    return reinterpret_cast<jlong>(new whisperstt());
}

JNIEXPORT jfloatArray JNICALL
Java_com_whisperstt_whisperengine_WhisperEngine_transcribeFileWithMel(JNIEnv *env,
                                                                      jobject thiz,
                                                                      jlong nativePtr,
                                                                      jstring waveFile,
                                                                      jfloatArray filtersJava) {
    whisperstt *engine = reinterpret_cast<whisperstt *>(nativePtr);
    const char *cWaveFile = env->GetStringUTFChars(waveFile, NULL);

    // Step 1: Get the native array from jfloatArray
    jfloat *nativeFiltersArray = env->GetFloatArrayElements(filtersJava, NULL);
    jsize filtersSize = env->GetArrayLength(filtersJava);

    // Step 2: Convert the native array to std::vector<float>
    std::vector<float> filtersVector(nativeFiltersArray, nativeFiltersArray + filtersSize);

    // Release the native array
    env->ReleaseFloatArrayElements(filtersJava, nativeFiltersArray, JNI_ABORT);

    // Call the engine method to transcribe the file and get the result as a vector of floats
    std::vector<float> result = engine->transcribeFileWithMel(cWaveFile, filtersVector);

    env->ReleaseStringUTFChars(waveFile, cWaveFile);

    // Convert the result vector to a jfloatArray
    jfloatArray resultArray = env->NewFloatArray(result.size());
    env->SetFloatArrayRegion(resultArray, 0, result.size(), result.data());

    return resultArray;
}

} // extern "C"
