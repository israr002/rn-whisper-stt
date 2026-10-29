#include <iostream>
#include <fstream>
#include <cstring>
#include <vector>
#include <thread>
#include <algorithm>
#include <sys/time.h>

#include "whisper.h"
#include "wav_util.h"
#include "whisperstt.h"

#include <android/log.h>

#define TIME_DIFF_MS(start, end) (((end.tv_sec - start.tv_sec) * 1000000) + (end.tv_usec - start.tv_usec))/1000

std::vector<float>
whisperstt::returnMelSpectrogram(std::vector<float> samples, std::vector<float> filtersJava) {
    timeval start_time{}, end_time{};
    gettimeofday(&start_time, NULL);

    samples.resize((WHISPER_SAMPLE_RATE * WHISPER_CHUNK_SIZE), 0);
    // Cap at 4: on big.LITTLE SoCs the extra threads land on LITTLE cores and slow the whole pass
    const auto processor_count = std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
    __android_log_print(ANDROID_LOG_INFO, "TRACKERS_mel", "%s", "before");

    log_mel_spectrogramJava(samples.data(), samples.size(), WHISPER_SAMPLE_RATE, WHISPER_N_FFT,
                            WHISPER_HOP_LENGTH, WHISPER_N_MEL, processor_count, filtersJava, mel);
    __android_log_print(ANDROID_LOG_INFO, "TRACKERS_mel", "%s", "after");

    gettimeofday(&end_time, NULL);
    std::cout << "Time taken for Spectrogram: " << TIME_DIFF_MS(start_time, end_time) << " ms" << std::endl;
    __android_log_print(ANDROID_LOG_INFO, "TRACKERS_mel", "Time taken for Spectrogram: %ld ms", TIME_DIFF_MS(start_time, end_time));

    return mel.data;
}

std::vector<float>
whisperstt::transcribeFileWithMel(const char *waveFile, std::vector<float> filtersJava) {
    std::vector<float> pcmf32 = readWAVFile(waveFile);
    __android_log_print(ANDROID_LOG_INFO, "TRACKERS_mel", "%s", "wav");

    pcmf32.resize((WHISPER_SAMPLE_RATE * WHISPER_CHUNK_SIZE), 0);
    __android_log_print(ANDROID_LOG_INFO, "TRACKERS_mel", "%s", "resize");
    std::vector<float> mel_spectrogram = returnMelSpectrogram(pcmf32, filtersJava);
    __android_log_print(ANDROID_LOG_INFO, "TRACKERS_mel", "%s", "mel-end");
    return mel_spectrogram;
}

std::vector<float>
whisperstt::melFromSamples(const float *samples, size_t n, const std::vector<float> &filters) {
    const size_t n_total = WHISPER_SAMPLE_RATE * WHISPER_CHUNK_SIZE;
    if (n > n_total) n = n_total;
    std::vector<float> padded(n_total, 0.0f);
    std::copy(samples, samples + n, padded.begin());

    const auto n_threads = std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
    log_mel_spectrogramJava(padded.data(), padded.size(), WHISPER_SAMPLE_RATE, WHISPER_N_FFT,
                            WHISPER_HOP_LENGTH, WHISPER_N_MEL, n_threads, filters, mel, (int) n);
    return mel.data;
}
