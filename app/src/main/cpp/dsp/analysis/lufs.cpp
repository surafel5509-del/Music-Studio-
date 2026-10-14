#include "lufs.h"
#include <cmath>
#include <algorithm>

namespace wavestudio {

static const double kPi = 3.14159265358979323846;

static inline float power_to_lufs(double p) {
    if (!(p > 1e-12)) return LufsMeter::kSilence;
    return (float)(-0.691 + 10.0 * std::log10(p));
}

void LufsMeter::set_sample_rate(float sr) {
    sr_ = sr > 0 ? sr : 48000.0;
    // BS.1770 stage 1: high-shelf (head model). Stage 2: RLB high-pass. Coefficients are derived
    // analytically so they are right at every sample rate, not only at 48 kHz.
    const double f0 = 1681.974450955533, G = 3.999843853973347, Q = 0.7071752369554196;
    double K = std::tan(kPi * f0 / sr_);
    double Vh = std::pow(10.0, G / 20.0);
    double Vb = std::pow(Vh, 0.4996667741545416);
    double a0 = 1.0 + K / Q + K * K;
    Biquad s;
    s.b0 = (Vh + Vb * K / Q + K * K) / a0;
    s.b1 = 2.0 * (K * K - Vh) / a0;
    s.b2 = (Vh - Vb * K / Q + K * K) / a0;
    s.a1 = 2.0 * (K * K - 1.0) / a0;
    s.a2 = (1.0 - K / Q + K * K) / a0;
    const double fh = 38.13547087602444, Qh = 0.5003270373238773;
    K = std::tan(kPi * fh / sr_);
    a0 = 1.0 + K / Qh + K * K;
    Biquad h;
    h.b0 = 1.0; h.b1 = -2.0; h.b2 = 1.0;
    h.a1 = 2.0 * (K * K - 1.0) / a0;
    h.a2 = (1.0 - K / Qh + K * K) / a0;
    // BS.1770 specifies the RLB numerator as 1, -2, 1 (not divided by a0).
    for (int c = 0; c < 2; ++c) { shelf_[c] = s; rlb_[c] = h; }
    hop_ = (int)std::lround(sr_ * 0.1);
    reset();
}

void LufsMeter::reset() {
    for (int c = 0; c < 2; ++c) { shelf_[c].z1 = shelf_[c].z2 = 0; rlb_[c].z1 = rlb_[c].z2 = 0; }
    hop_count_ = 0; hop_sum_ = 0;
    hops_.assign(30, 0.0);
    hop_ring_pos_ = 0; hop_ring_n_ = 0;
    gating_blocks_.clear();
    momentary_ = short_term_ = integrated_ = kSilence;
    peak_ = 0.f;
}

float LufsMeter::sample_peak_db() const {
    return peak_ > 1e-9f ? 20.f * std::log10(peak_) : -120.f;
}

void LufsMeter::process_interleaved(const float* x, int frames, int channels) {
    if (channels < 1) return;
    for (int i = 0; i < frames; ++i) {
        double p = 0;
        for (int c = 0; c < std::min(channels, 2); ++c) {
            const float v = x[i * channels + c];
            const double d = std::isfinite(v) ? v : 0.0;
            peak_ = std::max(peak_, (float)std::fabs(d));
            const double y = rlb_[c].tick(shelf_[c].tick(d));
            p += y * y;                          // channel weight 1.0 for L and R
        }
        if (channels == 1) p *= 2.0;             // treat mono as dual-mono (+3 dB) like the reference
        hop_sum_ += p;
        if (++hop_count_ >= hop_) {
            push_square(hop_sum_ / hop_count_);
            hop_count_ = 0; hop_sum_ = 0;
        }
    }
}

void LufsMeter::process(const float* const* planar, int frames, int channels) {
    // Small helper: interleave in fixed chunks without heap allocation.
    float tmp[512 * 2];
    const int ch = std::min(channels, 2);
    for (int base = 0; base < frames; base += 512) {
        const int n = std::min(512, frames - base);
        for (int i = 0; i < n; ++i)
            for (int c = 0; c < ch; ++c) tmp[i * ch + c] = planar[c][base + i];
        process_interleaved(tmp, n, ch);
    }
}

void LufsMeter::push_square(double power) {
    hops_[hop_ring_pos_] = power;
    hop_ring_pos_ = (hop_ring_pos_ + 1) % 30;
    if (hop_ring_n_ < 30) ++hop_ring_n_;
    auto mean_last = [&](int n) {
        double s = 0;
        for (int k = 0; k < n; ++k) s += hops_[(hop_ring_pos_ - 1 - k + 60) % 30];
        return s / n;
    };
    if (hop_ring_n_ >= 4) {
        const double m = mean_last(4);                         // 400 ms
        momentary_ = power_to_lufs(m);
        gating_blocks_.push_back(m);
        if (gating_blocks_.size() > 200000) gating_blocks_.erase(gating_blocks_.begin(), gating_blocks_.begin() + 100000);
        update_integrated();
    }
    short_term_ = hop_ring_n_ >= 30 ? power_to_lufs(mean_last(30)) : power_to_lufs(mean_last(hop_ring_n_));
}

void LufsMeter::update_integrated() {
    const double absGate = std::pow(10.0, (-70.0 + 0.691) / 10.0);
    double sum = 0; size_t n = 0;
    for (double p : gating_blocks_) if (p > absGate) { sum += p; ++n; }
    if (n == 0) { integrated_ = kSilence; return; }
    const double relGate = (sum / n) * 0.1;                    // -10 LU
    sum = 0; n = 0;
    for (double p : gating_blocks_) if (p > absGate && p > relGate) { sum += p; ++n; }
    integrated_ = n ? power_to_lufs(sum / n) : kSilence;
}

void PhaseMeter::process(const float* L, const float* R, int n) {
    for (int i = 0; i < n; ++i) {
        const double l = std::isfinite(L[i]) ? L[i] : 0.0, r = std::isfinite(R[i]) ? R[i] : 0.0;
        lr_ += l * r; ll_ += l * l; rr_ += r * r;
    }
    lr_ *= 0.999; ll_ *= 0.999; rr_ *= 0.999;      // slow decay: a running window, not the whole file
    const double d = std::sqrt(ll_ * rr_);
    corr_ = d > 1e-12 ? (float)std::max(-1.0, std::min(1.0, lr_ / d)) : 0.f;
}

} // namespace wavestudio
