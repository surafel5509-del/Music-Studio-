#pragma once
#include <vector>
#include <cstdint>
#include <complex>
#include <atomic>

namespace wavestudio {

class SpectrumAnalyzer {
public:
    SpectrumAnalyzer();
    void init(int fftSize, float sampleRate);
    void push(const float* frames, int n, int channels);
    void get_magnitudes(float* outBins, int bins) const;
    float sample_rate() const { return sample_rate_; }
    int fft_size() const { return n_; }

private:
    void process_window();
    static void fft(std::vector<std::complex<float>>& a, bool invert);
    int n_ = 2048;
    float sample_rate_ = 48000.f;
    std::vector<float> window_;
    std::vector<float> buffer_;
    int buf_ptr_ = 0;
    std::vector<float> mags_;
    mutable std::mutex m_;
};

} // namespace wavestudio
