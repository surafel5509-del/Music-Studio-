#include "voice_fx.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace wavestudio {

namespace {
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float finite(float v) { return std::isfinite(v) ? v : 0.f; }
int next_pow2(int v) { int p = 1; while (p < v) p <<= 1; return p; }
constexpr float kPi = 3.14159265358979f;
}

// =================================================================== PitchDetector
void PitchDetector::init(float sr) {
    sr_d_ = sr * 0.5f;
    W_ = std::max(256, (int)(0.027f * sr_d_));
    tauMin_ = std::max(2, (int)std::ceil(sr_d_ / kMaxHz));
    tauMax_ = std::max(tauMin_ + 4, (int)(sr_d_ / kMinHz));
    hop_ = std::max(128, (int)(0.0107f * sr_d_));
    const int total = W_ + tauMax_ + hop_ + 8;
    const int n = next_pow2(total);
    ring_.assign(n, 0.f); ringMask_ = n - 1;
    lin_.assign(W_ + tauMax_ + 2, 0.f);
    d_.assign(tauMax_ + 2, 0.f); dp_.assign(tauMax_ + 2, 1.f);
    reset();
}
void PitchDetector::reset() {
    std::fill(ring_.begin(), ring_.end(), 0.f);
    wp_ = 0; cnt_ = 0; odd_ = false; prev_ = 0.f; f0_ = 0.f; conf_ = 0.f;
}
bool PitchDetector::push(float x) {
    x = finite(x);
    if (!odd_) { prev_ = x; odd_ = true; return false; }
    odd_ = false;
    ring_[wp_] = 0.5f * (prev_ + x);
    wp_ = (wp_ + 1) & ringMask_;
    if (++cnt_ >= hop_) { cnt_ = 0; detect(); return true; }
    return false;
}
void PitchDetector::detect() {
    const int total = W_ + tauMax_;
    const int start = wp_ - total;
    for (int i = 0; i < total; ++i) lin_[i] = ring_[(start + i) & ringMask_];
    float e = 0.f;
    for (int j = 0; j < W_; ++j) e += lin_[j] * lin_[j];
    e /= (float)W_;
    f0_ = 0.f; conf_ = 0.f;
    if (e < 1e-5f) return;                                  // below about -50 dBFS RMS: do not track noise
    for (int tau = 0; tau <= tauMax_; ++tau) {
        float s = 0.f;
        const float* a = lin_.data(); const float* b = lin_.data() + tau;
        for (int j = 0; j < W_; ++j) { const float v = a[j] - b[j]; s += v * v; }
        d_[tau] = s;
    }
    dp_[0] = 1.f;
    float cum = 0.f;
    for (int tau = 1; tau <= tauMax_; ++tau) {
        cum += d_[tau];
        dp_[tau] = cum > 1e-12f ? d_[tau] * (float)tau / cum : 1.f;
    }
    int best = -1;
    for (int tau = tauMin_; tau <= tauMax_; ++tau) {
        if (dp_[tau] < 0.15f) {
            while (tau + 1 <= tauMax_ && dp_[tau + 1] < dp_[tau]) ++tau;
            best = tau; break;
        }
    }
    if (best < 0) {
        float m = 1e9f;
        for (int tau = tauMin_; tau <= tauMax_; ++tau) if (dp_[tau] < m) { m = dp_[tau]; best = tau; }
        if (best < 0 || m > 0.35f) return;                  // not periodic enough: unvoiced
    }
    float tf = (float)best;
    if (best > tauMin_ && best < tauMax_) {
        const float a = dp_[best - 1], b = dp_[best], c = dp_[best + 1];
        const float den = a - 2.f * b + c;
        if (std::fabs(den) > 1e-9f) tf += clampf(0.5f * (a - c) / den, -1.f, 1.f);
    }
    if (tf < 1.f) return;
    f0_ = sr_d_ / tf;
    conf_ = clampf(1.f - dp_[best], 0.f, 1.f);
}

// =================================================================== GrainPitch
void GrainPitch::init(float sr, float maxWindowMs) {
    sr_ = sr;
    maxW_ = std::max(512.f, maxWindowMs * 0.001f * sr);
    maxEff_ = std::min(1.5f * maxW_, 0.09f * sr);
    const int n = next_pow2((int)(maxEff_ + maxW_) + 64);
    buf_[0].assign(n, 0.f); buf_[1].assign(n, 0.f); mask_ = n - 1;
    Wd_ = std::min(maxW_, 0.030f * sr);
    reset();
}
void GrainPitch::reset() {
    std::fill(buf_[0].begin(), buf_[0].end(), 0.f); std::fill(buf_[1].begin(), buf_[1].end(), 0.f);
    wp_ = 0; phase_ = 0.5f; Weff_ = Wd_;
}
void GrainPitch::set_window_ms(float ms) { Wd_ = clampf(ms * 0.001f * sr_, 0.020f * sr_, maxW_); }
float GrainPitch::delayed(int ch, int n) const {
    n = std::max(0, std::min(n, mask_ - 1));
    return buf_[ch][(wp_ - 1 - n) & mask_];
}
float GrainPitch::read(int ch, float d) const {
    int n0 = (int)d; float f = d - (float)n0;
    if (n0 < 1) { n0 = 1; f = 0.f; }
    if (n0 > mask_ - 4) { n0 = mask_ - 4; f = 0.f; }
    const float* b = buf_[ch].data();
    auto s = [&](int k) { return b[(wp_ - 1 - k) & mask_]; };
    const float p0 = s(n0 + 2), p1 = s(n0 + 1), p2 = s(n0), p3 = s(n0 - 1);
    const float t = 1.f - f;
    const float c1 = 0.5f * (p2 - p0), c2 = p0 - 2.5f * p1 + 2.f * p2 - 0.5f * p3, c3 = 0.5f * (p3 - p0) + 1.5f * (p1 - p2);
    return ((c3 * t + c2) * t + c1) * t + p1;
}
void GrainPitch::tick(float* io, int nc, float ratio, float period) {
    const int cn = std::min(2, std::max(1, nc));
    for (int c = 0; c < cn; ++c) buf_[c][wp_] = finite(io[c]);
    wp_ = (wp_ + 1) & mask_;
    ratio = clampf(ratio, 0.25f, 4.f);
    float Wt = Wd_;
    if (period > 8.f) {
        const int m = std::max(1, (int)std::lround(Wd_ / (2.f * period)));
        Wt = 2.f * (float)m * period;
    }
    Wt = clampf(Wt, 0.015f * sr_, maxEff_);
    Weff_ += (Wt - Weff_) * 0.0008f;                       // ~25 ms: the sweep length follows the period smoothly
    if (std::fabs(ratio - 1.f) < 1e-4f) {
        // no shift requested: glide to the point where only tap A is audible (a plain delay, perfectly transparent)
        const float step = 1.f / sr_;
        phase_ += clampf(0.5f - phase_, -step, step);
    } else {
        phase_ += (1.f - ratio) / Weff_;
    }
    phase_ -= std::floor(phase_);
    float pB = phase_ + 0.5f; if (pB >= 1.f) pB -= 1.f;
    const float dA = 4.f + phase_ * Weff_, dB = 4.f + pB * Weff_;
    const float s = std::sin(kPi * phase_);
    const float gA = s * s, gB = 1.f - gA;
    for (int c = 0; c < cn; ++c) io[c] = gA * read(c, dA) + gB * read(c, dB);
}

// =================================================================== Stft
void Stft::init() {
    fft_.init(kN);
    in_.assign(kN, 0.f); out_.assign(kN, 0.f); re_.assign(kN, 0.f); im_.assign(kN, 0.f); win_.assign(kN, 0.f);
    for (int i = 0; i < kN; ++i) win_[i] = std::sqrt(0.5f - 0.5f * std::cos(2.f * kPi * (float)i / (float)kN));
    ip_ = 0; cnt_ = 0;
}
void Stft::reset() {
    std::fill(in_.begin(), in_.end(), 0.f); std::fill(out_.begin(), out_.end(), 0.f); ip_ = 0; cnt_ = 0;
}

// =================================================================== PitchShift
PitchShift::PitchShift() {
    params_ = {
        {"semitones", "Semitones", -24, 24, 0, 0, "st"},
        {"cents", "Fine", -100, 100, 0, 0, "ct"},
        {"mix", "Mix", 0, 1, 1, 1, ""},
        {"window", "Window", 30, 60, 30, 30, "ms"},
        {"sync", "Voice sync", 0, 1, 1, 1, "", {"Off", "On"}},
    };
}
void PitchShift::set_sample_rate(float sr) {
    sample_rate_ = sr;
    gp_.init(sr, 60.f); det_.init(sr);
    ratio_ = 1.f; period_ = 0.f;
}
void PitchShift::reset() { gp_.reset(); det_.reset(); ratio_ = 1.f; period_ = 0.f; }
void PitchShift::process(float** x, int nf, int nc) {
    if (nc < 1) return;
    const int cn = std::min(2, nc);
    const float target = std::pow(2.f, (params_[0].value + params_[1].value * 0.01f) / 12.f);
    const float mix = params_[2].value;
    const bool sync = params_[4].value >= 0.5f;
    gp_.set_window_ms(params_[3].value);
    const int lat = gp_.nominal_latency();
    for (int i = 0; i < nf; ++i) {
        float io[2] = {x[0][i], cn > 1 ? x[1][i] : 0.f};
        if (sync && det_.push(cn > 1 ? 0.5f * (io[0] + io[1]) : io[0])) {
            const float f = det_.f0();
            if (f > 0.f) { const float p = sample_rate_ / f; period_ = period_ > 0.f ? period_ + 0.3f * (p - period_) : p; }
            else period_ = 0.f;
        }
        ratio_ += (target - ratio_) * 0.004f;
        gp_.tick(io, cn, ratio_, sync ? period_ : 0.f);
        for (int c = 0; c < cn; ++c) {
            const float dry = gp_.delayed(c, lat);
            x[c][i] = dry * (1.f - mix) + io[c] * mix;
        }
    }
}

// =================================================================== AutoTune
const std::vector<std::string>& AutoTune::scale_names() {
    static const std::vector<std::string> v = {"Chromatic", "Major", "Minor", "Harmonic minor", "Major pentatonic", "Minor pentatonic", "Blues", "Dorian"};
    return v;
}
const std::vector<std::string>& AutoTune::note_names() {
    static const std::vector<std::string> v = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    return v;
}
static const int kScaleMask[8] = {
    0xFFF,
    (1 << 0) | (1 << 2) | (1 << 4) | (1 << 5) | (1 << 7) | (1 << 9) | (1 << 11),
    (1 << 0) | (1 << 2) | (1 << 3) | (1 << 5) | (1 << 7) | (1 << 8) | (1 << 10),
    (1 << 0) | (1 << 2) | (1 << 3) | (1 << 5) | (1 << 7) | (1 << 8) | (1 << 11),
    (1 << 0) | (1 << 2) | (1 << 4) | (1 << 7) | (1 << 9),
    (1 << 0) | (1 << 3) | (1 << 5) | (1 << 7) | (1 << 10),
    (1 << 0) | (1 << 3) | (1 << 5) | (1 << 6) | (1 << 7) | (1 << 10),
    (1 << 0) | (1 << 2) | (1 << 3) | (1 << 5) | (1 << 7) | (1 << 9) | (1 << 10)};

float AutoTune::nearest_scale_note(float midi, int key, int scale, float previous) {
    scale = std::max(0, std::min(7, scale));
    key = ((key % 12) + 12) % 12;
    const int mask = kScaleMask[scale];
    auto allowed = [&](int n) { return (mask >> ((((n - key) % 12) + 12) % 12)) & 1; };
    // hysteresis: stay on the previous note until the pitch is clearly closer to another one
    if (previous >= 0.f && allowed((int)previous) && std::fabs(midi - previous) < 0.65f) return previous;
    const int c = (int)std::lround(midi);
    float best = (float)c, bd = 1e9f;
    for (int n = c - 7; n <= c + 7; ++n) {
        if (!allowed(n)) continue;
        const float d = std::fabs((float)n - midi);
        if (d < bd) { bd = d; best = (float)n; }
    }
    return best;
}

AutoTune::AutoTune() {
    params_ = {
        {"key", "Key", 0, 11, 0, 0, "", note_names()},
        {"scale", "Scale", 0, 7, 0, 0, "", scale_names()},
        {"speed", "Retune speed", 0, 400, 20, 20, "ms"},
        {"amount", "Correction", 0, 1, 1, 1, ""},
        {"ref", "A4 tuning", 415, 466, 440, 440, "Hz"},
    };
}
void AutoTune::set_sample_rate(float sr) {
    sample_rate_ = sr; gp_.init(sr, 40.f); det_.init(sr); reset();
}
void AutoTune::reset() { gp_.reset(); det_.reset(); st_ = target_st_ = period_ = 0.f; last_note_ = -1.f; hold_ = 0; }
void AutoTune::process(float** x, int nf, int nc) {
    if (nc < 1) return;
    const int cn = std::min(2, nc);
    const int key = (int)std::lround(params_[0].value), scale = (int)std::lround(params_[1].value);
    const float speed = params_[2].value, amount = params_[3].value, ref = params_[4].value;
    const float coef = speed <= 0.5f ? 1.f : 1.f - std::exp(-1.f / (speed * 0.001f * sample_rate_));
    const float coefIdle = 1.f - std::exp(-1.f / (0.03f * sample_rate_));
    const int holdMax = (int)(0.08f * sample_rate_);
    for (int i = 0; i < nf; ++i) {
        float io[2] = {x[0][i], cn > 1 ? x[1][i] : 0.f};
        if (det_.push(cn > 1 ? 0.5f * (io[0] + io[1]) : io[0])) {
            const float f = det_.f0();
            if (f > 0.f) {
                const float midi = 69.f + 12.f * std::log2(f / ref);
                const float tgt = nearest_scale_note(midi, key, scale, last_note_);
                last_note_ = tgt;
                target_st_ = clampf(tgt - midi, -12.f, 12.f) * amount;
                const float p = sample_rate_ / f;
                period_ = period_ > 0.f ? period_ + 0.4f * (p - period_) : p;
                hold_ = holdMax;
            } else {
                last_note_ = -1.f;
                if (hold_ <= 0) { target_st_ = 0.f; period_ = 0.f; }
            }
        }
        if (hold_ > 0) --hold_;
        const bool voiced = hold_ > 0;
        st_ += (target_st_ - st_) * (voiced ? coef : coefIdle);
        gp_.tick(io, cn, std::pow(2.f, st_ / 12.f), period_);
        for (int c = 0; c < cn; ++c) x[c][i] = io[c];
    }
}

// =================================================================== VoiceChanger
VoiceChanger::VoiceChanger() {
    params_ = {
        {"pitch", "Pitch", -12, 12, 0, 0, "st"},
        {"formant", "Formant", -12, 12, 0, 0, "st"},
        {"robot", "Robot", 0, 1, 0, 0, ""},
        {"robot_freq", "Robot freq", 20, 500, 60, 60, "Hz"},
    };
}
void VoiceChanger::set_sample_rate(float sr) {
    sample_rate_ = sr;
    stft_[0].init(); stft_[1].init();
    gp_.init(sr, 40.f); det_.init(sr);
    env_.assign(Stft::kN / 2 + 8, 0.f); tmp_.assign(Stft::kN / 2 + 8, 0.f); mag_.assign(Stft::kN / 2 + 8, 0.f);
    reset();
}
void VoiceChanger::reset() {
    stft_[0].reset(); stft_[1].reset(); gp_.reset(); det_.reset();
    ratio_ = 1.f; period_ = 0.f; robot_phase_ = 0.f;
}
void VoiceChanger::process(float** x, int nf, int nc) {
    if (nc < 1) return;
    const int cn = std::min(2, nc);
    const float pitch = params_[0].value, formant = params_[1].value, robot = params_[2].value;
    const float rfreq = params_[3].value;
    // The grain shifter scales formants together with pitch; pre-warp the spectral envelope so the net formant
    // movement is exactly `formant` semitones.
    const float warp = std::pow(2.f, (formant - pitch) / 12.f);
    const bool doWarp = std::fabs(warp - 1.f) > 0.005f;
    const float target = std::pow(2.f, pitch / 12.f);
    const int half = Stft::kN / 2;
    auto spectral = [&](float* re, float* im) {
        if (!doWarp) return;
        for (int k = 0; k <= half; ++k) mag_[k] = std::sqrt(re[k] * re[k] + im[k] * im[k]);
        // envelope: two passes of a 9-bin box filter (~190 Hz at 48 kHz) -> follows formants, not harmonics
        for (int pass = 0; pass < 2; ++pass) {
            const float* src = pass == 0 ? mag_.data() : tmp_.data();
            float* dst = pass == 0 ? tmp_.data() : env_.data();
            for (int k = 0; k <= half; ++k) {
                float s = 0.f; int n = 0;
                for (int j = std::max(0, k - 4); j <= std::min(half, k + 4); ++j) { s += src[j]; ++n; }
                dst[k] = s / (float)n;
            }
        }
        for (int k = 0; k <= half; ++k) {
            const float srcBin = (float)k / warp;
            float ew;
            if (srcBin >= (float)half) ew = env_[half] * 0.1f;
            else { const int i0 = (int)srcBin; const float fr = srcBin - (float)i0; ew = env_[i0] * (1.f - fr) + env_[std::min(half, i0 + 1)] * fr; }
            const float g = clampf(ew / (env_[k] + 1e-7f), 0.05f, 8.f);
            re[k] *= g; im[k] *= g;
        }
    };
    for (int i = 0; i < nf; ++i) {
        float io[2];
        for (int c = 0; c < cn; ++c) io[c] = stft_[c].tick(finite(x[c][i]), spectral);
        if (det_.push(cn > 1 ? 0.5f * (io[0] + io[1]) : io[0])) {
            const float f = det_.f0();
            if (f > 0.f) { const float p = sample_rate_ / f; period_ = period_ > 0.f ? period_ + 0.3f * (p - period_) : p; }
            else period_ = 0.f;
        }
        ratio_ += (target - ratio_) * 0.004f;
        if (std::fabs(ratio_ - 1.f) > 1e-4f || std::fabs(target - 1.f) > 1e-4f) gp_.tick(io, cn, ratio_, period_);
        else gp_.tick(io, cn, 1.f, period_);
        if (robot > 0.001f) {
            robot_phase_ += rfreq / sample_rate_; robot_phase_ -= std::floor(robot_phase_);
            const float m = std::sin(2.f * kPi * robot_phase_);
            for (int c = 0; c < cn; ++c) io[c] = io[c] * (1.f - robot) + io[c] * m * robot;
        }
        for (int c = 0; c < cn; ++c) x[c][i] = io[c];
    }
}

// =================================================================== Denoise
Denoise::Denoise() {
    params_ = {
        {"reduction", "Reduction", 0, 40, 18, 18, "dB"},
        {"sensitivity", "Sensitivity", 0, 1, 0.5f, 0.5f, ""},
    };
    set_sample_rate(48000.f);
}
void Denoise::set_sample_rate(float sr) {
    sample_rate_ = sr;
    const int half = Stft::kN / 2;
    for (auto& c : ch_) {
        c.stft.init();
        c.noise.assign(half + 2, 0.f); c.ps.assign(half + 2, 0.f); c.gain.assign(half + 2, 1.f); c.frames = 0;
    }
    g_raw_.assign(half + 2, 1.f); g_sm_.assign(half + 2, 1.f);
}
void Denoise::reset() {
    for (auto& c : ch_) {
        c.stft.reset();
        std::fill(c.noise.begin(), c.noise.end(), 0.f); std::fill(c.ps.begin(), c.ps.end(), 0.f);
        std::fill(c.gain.begin(), c.gain.end(), 1.f); c.frames = 0;
    }
}
void Denoise::process(float** x, int nf, int nc) {
    if (nc < 1) return;
    const int cn = std::min(2, nc);
    const int half = Stft::kN / 2;
    const float gmin = db_to_linear(-params_[0].value);
    const float alpha = 2.5f + 4.0f * params_[1].value;
    for (int c = 0; c < cn; ++c) {
        Chan& ch = ch_[c];
        auto spectral = [&](float* re, float* im) {
            for (int k = 0; k <= half; ++k) {
                const float P = re[k] * re[k] + im[k] * im[k];
                float& ps = ch.ps[k];
                ps = ch.frames == 0 ? P : 0.75f * ps + 0.25f * P;
                float& nz = ch.noise[k];
                if (ch.frames < 6) nz = ch.frames == 0 ? ps : 0.5f * (nz + ps);   // learn from the very first frames
                else if (ps < nz) nz = 0.7f * nz + 0.3f * ps;
                else nz = std::min(ps, nz * 1.002f);                            // slow rise: ~x2 in 2 s at 48 kHz
            }
            ++ch.frames;
            for (int k = 0; k <= half; ++k) {
                const float P = 0.3f * (re[k] * re[k] + im[k] * im[k]) + 0.7f * ch.ps[k];
                float g = P > 1e-14f ? 1.f - alpha * ch.noise[k] / P : gmin;
                g_raw_[k] = clampf(g, gmin, 1.f);
            }
            for (int k = 0; k <= half; ++k) {                                    // frequency smoothing
                const float a = g_raw_[std::max(0, k - 1)], b = g_raw_[k], d = g_raw_[std::min(half, k + 1)];
                float g = 0.25f * a + 0.5f * b + 0.25f * d;
                float& pg = ch.gain[k];
                g = g > pg ? pg + 0.6f * (g - pg) : 0.7f * pg + 0.3f * g;       // fast attack, slower release
                pg = g;
                re[k] *= g; im[k] *= g;
            }
        };
        for (int i = 0; i < nf; ++i) x[c][i] = ch.stft.tick(finite(x[c][i]), spectral);
    }
}

} // namespace wavestudio
