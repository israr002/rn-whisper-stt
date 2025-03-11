#ifndef WHISPERSTT_H
#define WHISPERSTT_H

#include <string>
#include <vector>

class whisperstt {
public:
    whisperstt() {};
    ~whisperstt() {};
    std::vector<float> transcribeFileWithMel(const char *waveFile, std::vector<float> filters);
    std::vector<float> returnMelSpectrogram(std::vector<float> samples, std::vector<float> filters);

private:
    // Add any private members or helper functions.
};

#endif // WHISPERSTT_H
