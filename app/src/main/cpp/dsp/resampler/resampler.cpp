#include "resampler.h"
#include <algorithm>
#include <cmath>

namespace wavestudio {

namespace {
constexpr int kZeroCrossings = 24;
constexpr int kPhases = 512;

inline double sinc(double x) {
    if (std::fabs(x) < 1e-9) return 1.0;
    const double px = 3.14159265358979323846 * x;
    return std::sin(px) / px;
}
inline double blackman_harris(double t) { // t in [-1, 1]
    if (t <= -1.0 || t >= 1.0) return 0.0;
    const double a = 3.14159265358979323846 * (t + 1.0); // 0..2pi
    return 0.35875 - 0.48829 * std::cos(a) + 0.14128 * std::cos(2 * a) - 0.01168 * std::cos(3 * a);
}
} // namespace

std::vector<float> resample_interleaved(const float* in, int64_t frames, int channels,
                                        float in_rate, float out_rate) {
    if (frames <= 0 || channels <= 0 || in_rate <= 0 || out_rate <= 0) return {};
    if (std::fabs(in_rate - out_rate) < 0.5f) return std::vector<float>(in, in + frames * channels);

    const double ratio = (double)out_rate / in_rate;          // out samples per in sample
    const double cutoff = std::min(1.0, ratio);                // normalised to input Nyquist
    const int half = (int)std::ceil(kZeroCrossings / cutoff);  // kernel half-width in input samples
    const int64_t out_frames = (int64_t)std::floor((double)frames * ratio);
    std::vector<float> out((size_t)(out_frames * channels), 0.f);

    // Pre-computed kernel table over fractional offset, linear-interpolated.
    const int taps = 2 * half + 1;
    std::vector<float> table((size_t)(kPhases + 1) * taps);
    for (int p = 0; p <= kPhases; ++p) {
        double frac = (double)p / kPhases;
        double sum = 0.0;
        for (int k = 0; k < taps; ++k) {
            double x = (k - half) - frac;               // distance from output position
            double w = cutoff * sinc(cutoff * x) * blackman_harris(x / (half + 1.0));
            table[(size_t)p * taps + k] = (float)w;
            sum += w;
        }
        if (sum != 0.0) for (int k = 0; k < taps; ++k) table[(size_t)p * taps + k] = (float)(table[(size_t)p * taps + k] / sum);
    }

    for (int64_t o = 0; o < out_frames; ++o) {
        double src = (double)o / ratio;
        int64_t base = (int64_t)std::floor(src);
        double frac = src - base;
        double ph = frac * kPhases;
        int p0 = (int)ph;
        float pf = (float)(ph - p0);
        const float* t0 = &table[(size_t)p0 * taps];
        const float* t1 = &table[(size_t)(p0 + 1) * taps];
        for (int c = 0; c < channels; ++c) {
            float acc = 0.f;
            for (int k = 0; k < taps; ++k) {
                int64_t idx = base + (k - half);
                if (idx < 0 || idx >= frames) continue;
                float w = t0[k] + (t1[k] - t0[k]) * pf;
                acc += w * in[idx * channels + c];
            }
            out[(size_t)(o * channels + c)] = acc;
        }
    }
    return out;
}

std::shared_ptr<AudioBuffer> resample_buffer(const std::shared_ptr<AudioBuffer>& src, float target_rate) {
    if (!src || target_rate <= 0.f) return src;
    if (std::fabs(src->sample_rate() - target_rate) < 0.5f) return src;
    std::vector<float> data = resample_interleaved(src->data().data(), src->frames(), src->channels(),
                                                   src->sample_rate(), target_rate);
    if (data.empty()) return src;
    auto out = std::make_shared<AudioBuffer>();
    const int64_t frames = (int64_t)data.size() / src->channels();
    out->allocate(src->channels(), frames, target_rate);
    std::copy(data.begin(), data.end(), out->data_mut().begin());
    out->build_peak_table();
    return out;
}

} // namespace wavestudio
