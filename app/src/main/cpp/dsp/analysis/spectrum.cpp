#include "spectrum.h"
#include <cmath>
#include <cstring>
#include <mutex>
#include <algorithm>

namespace wavestudio {

SpectrumAnalyzer::SpectrumAnalyzer() { init(2048, 48000.f); }

void SpectrumAnalyzer::init(int fftSize, float sampleRate) {
    std::lock_guard<std::mutex> lk(m_);
    n_ = fftSize;
    sample_rate_ = sampleRate;
    window_.resize(n_);
    for (int i = 0; i < n_; ++i) {
        // Hann window
        window_[i] = 0.5f * (1 - cosf(2 * 3.1415926535f * i / (n_-1)));
    }
    buffer_.assign(n_, 0.f);
    mags_.assign(n_/2, 0.f);
    buf_ptr_ = 0;
}

void SpectrumAnalyzer::push(const float* frames, int n, int channels) {
    std::lock_guard<std::mutex> lk(m_);
    for (int i = 0; i < n; ++i) {
        // mix to mono
        float m = frames[i*channels];
        if (channels > 1) m = (m + frames[i*channels+1]) * 0.5f;
        buffer_[buf_ptr_++] = m;
        if (buf_ptr_ >= n_) {
            process_window();
            buf_ptr_ = n_ / 2; // 50% overlap
            std::memmove(buffer_.data(), buffer_.data() + n_/2, sizeof(float)*n_/2);
        }
    }
}

void SpectrumAnalyzer::process_window() {
    std::vector<std::complex<float>> f(n_);
    for (int i=0;i<n_;++i) f[i] = buffer_[i] * window_[i];
    fft(f, false);
    float norm = 2.f / n_;
    for (int i=0;i<n_/2;++i){
        float m = std::abs(f[i]) * norm;
        // smooth
        mags_[i] = mags_[i]*0.7f + m*0.3f;
    }
}

void SpectrumAnalyzer::get_magnitudes(float* outBins, int bins) const {
    std::lock_guard<std::mutex> lk(m_);
    if (mags_.empty()) { std::memset(outBins, 0, sizeof(float)*bins); return; }
    // Map linear FFT bins to requested bins (log scale)
    for (int i=0;i<bins;++i) {
        float t = (float)i/(bins-1);
        float fmin = 20.f, fmax = sample_rate_/2.f;
        float freq = fmin * powf(fmax/fmin, t);
        float bin = freq * n_ / sample_rate_;
        int b0 = (int)bin;
        if (b0 < 0) b0 = 0;
        if (b0 >= n_/2-1) b0 = n_/2-2;
        float frac = bin - b0;
        float m = mags_[b0]*(1-frac) + mags_[b0+1]*frac;
        outBins[i] = 20.f*log10f(m + 1e-10f);
    }
}

// Cooley-Tukey FFT (power-of-two)
void SpectrumAnalyzer::fft(std::vector<std::complex<float>>& a, bool invert) {
    int n = (int)a.size();
    for (int i=1,j=0;i<n;++i){
        int bit = n>>1;
        for (;j&bit;bit>>=1) j^=bit;
        j^=bit;
        if (i<j) std::swap(a[i],a[j]);
    }
    for (int len=2;len<=n;len<<=1){
        float ang = 2*3.1415926535f/len*(invert?-1:1);
        std::complex<float> wlen(cosf(ang),sinf(ang));
        for(int i=0;i<n;i+=len){
            std::complex<float> w(1);
            for(int j=0;j<len/2;++j){
                auto u = a[i+j], v = a[i+j+len/2]*w;
                a[i+j]=u+v;
                a[i+j+len/2]=u-v;
                w*=wlen;
            }
        }
    }
    if (invert) for (auto& x:a) x/=n;
}

} // namespace wavestudio
