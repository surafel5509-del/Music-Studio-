#pragma once
// Small in-place radix-2 complex FFT with precomputed tables (no allocation after init()).
#include <cmath>
#include <vector>

namespace wavestudio {

class FFT {
public:
    void init(int n) {                       // n: power of two
        n_ = n;
        cs_.assign(n / 2, 0.f); sn_.assign(n / 2, 0.f); rev_.assign(n, 0);
        for (int k = 0; k < n / 2; ++k) {
            cs_[k] = (float)std::cos(2.0 * M_PI * k / n);
            sn_[k] = (float)std::sin(2.0 * M_PI * k / n);
        }
        int bits = 0; while ((1 << bits) < n) ++bits;
        for (int i = 0; i < n; ++i) {
            int r = 0; for (int b = 0; b < bits; ++b) if (i & (1 << b)) r |= 1 << (bits - 1 - b);
            rev_[i] = r;
        }
    }
    int size() const { return n_; }
    // Forward: X[k] = sum x[n] e^{-2 pi i k n / N}.  Inverse includes the 1/N scale.
    void transform(float* re, float* im, bool inverse) const {
        for (int i = 0; i < n_; ++i) { int j = rev_[i]; if (j > i) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); } }
        for (int len = 2; len <= n_; len <<= 1) {
            const int half = len >> 1, step = n_ / len;
            for (int i = 0; i < n_; i += len) {
                for (int k = 0; k < half; ++k) {
                    const float wr = cs_[k * step], wi = inverse ? sn_[k * step] : -sn_[k * step];
                    const int a = i + k, b = a + half;
                    const float tr = re[b] * wr - im[b] * wi, ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr;        im[a] += ti;
                }
            }
        }
        if (inverse) { const float s = 1.f / (float)n_; for (int i = 0; i < n_; ++i) { re[i] *= s; im[i] *= s; } }
    }
private:
    int n_ = 0;
    std::vector<float> cs_, sn_;
    std::vector<int> rev_;
};

} // namespace wavestudio
