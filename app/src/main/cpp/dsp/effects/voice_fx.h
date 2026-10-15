#pragma once
// Voice / pitch effects: pitch shifter, auto-tune (pitch correction), voice changer (pitch + formant + robot),
// noise reduction (spectral gating). All are real-time safe: buffers are allocated in set_sample_rate() (control
// thread), process() never allocates, locks or does I/O.
//
// LATENCY: these effects are not zero-latency (pitch shift ~ window, STFT based ones +21 ms). The engine has no plugin
// delay compensation yet, so tracks using them are delayed relative to others; use them on recorded vocals and nudge the
// clip, or render. This is documented in the UI/README.
#include "effect.h"
#include "util/fft.h"
#include <memory>
#include <vector>

namespace wavestudio {

// ---------------------------------------------------------------- pitch detector (YIN)
class PitchDetector {
public:
    void init(float sampleRate);
    void reset();
    // Feed one full-rate sample. Returns true when a new estimate became available.
    bool push(float x);
    float f0() const { return f0_; }              // Hz, 0 when unvoiced / too quiet
    float confidence() const { return conf_; }    // 0..1
    static constexpr float kMinHz = 70.f, kMaxHz = 1000.f;
private:
    void detect();
    float sr_d_ = 24000.f;
    int W_ = 648, tauMin_ = 24, tauMax_ = 342, hop_ = 256, cnt_ = 0;
    bool odd_ = false; float prev_ = 0.f;
    std::vector<float> ring_, lin_, d_, dp_;
    int ringMask_ = 0, wp_ = 0;
    float f0_ = 0.f, conf_ = 0.f;
};

// ---------------------------------------------------------------- pitch-synchronous grain shifter
// Two delay-line taps half a sweep apart, cross-faded with sin^2/cos^2 windows (gains always sum to 1). When the
// input period T is known the sweep length is set to an even multiple of T (2*m*T), so the two taps are an exact
// multiple of the period apart and add in phase: no comb filtering or warble on voiced sounds. Without a period it
// uses the nominal window. Both taps' delays are continuous wherever their gain is non-zero, so the output pitch is
// exactly input * ratio.
class GrainPitch {
public:
    void init(float sampleRate, float maxWindowMs);
    void reset();
    void set_window_ms(float ms);
    // In place: io[0..nc-1] are one sample per channel. ratio = output/input frequency. period in samples (0 = unknown).
    void tick(float* io, int nc, float ratio, float period);
    float delayed(int ch, int n) const;                  // dry signal delayed by n samples
    int nominal_latency() const { return 4 + (int)(0.5f * Wd_); }   // mean delay of the shifted path
private:
    float read(int ch, float d) const;
    std::vector<float> buf_[2];
    int mask_ = 0, wp_ = 0;
    float sr_ = 48000.f, Wd_ = 1440.f, maxW_ = 2880.f, maxEff_ = 4320.f, Weff_ = 1440.f, phase_ = 0.5f;
};

// ---------------------------------------------------------------- STFT helper (1024 / hop 256, sqrt-Hann), latency kN
class Stft {
public:
    static constexpr int kN = 1024, kH = 256;
    void init();
    void reset();
    template <class F>
    float tick(float x, F&& spectral) {
        float y = out_[ip_]; out_[ip_] = 0.f; in_[ip_] = x; ip_ = (ip_ + 1) & (kN - 1);
        if (++cnt_ >= kH) {
            cnt_ = 0;
            for (int i = 0; i < kN; ++i) { re_[i] = in_[(ip_ + i) & (kN - 1)] * win_[i]; im_[i] = 0.f; }
            fft_.transform(re_.data(), im_.data(), false);
            spectral(re_.data(), im_.data());
            // keep the spectrum conjugate-symmetric so the inverse is real
            for (int k = 1; k < kN / 2; ++k) { re_[kN - k] = re_[k]; im_[kN - k] = -im_[k]; }
            im_[0] = 0.f; im_[kN / 2] = 0.f;
            fft_.transform(re_.data(), im_.data(), true);
            for (int i = 0; i < kN; ++i) out_[(ip_ + i) & (kN - 1)] += re_[i] * win_[i] * 0.5f;
        }
        return y;
    }
private:
    FFT fft_;
    std::vector<float> in_, out_, win_, re_, im_;
    int ip_ = 0, cnt_ = 0;
};

// ---------------------------------------------------------------- effects
class PitchShift : public Effect {
public:
    PitchShift();
    const char* name() const override { return "Pitch Shift / Harmonizer"; }
    const char* type_id() const override { return "pitch_shift"; }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int nf, int nc) override;
private:
    GrainPitch gp_; PitchDetector det_;
    float ratio_ = 1.f, period_ = 0.f;
};

class AutoTune : public Effect {
public:
    AutoTune();
    const char* name() const override { return "Auto-Tune"; }
    const char* type_id() const override { return "autotune"; }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int nf, int nc) override;
    // Nearest allowed note (MIDI number, fractional input) for a key (0 = C) and scale index. Pure function.
    static float nearest_scale_note(float midi, int key, int scale, float previous);
    static const std::vector<std::string>& scale_names();
    static const std::vector<std::string>& note_names();
    float current_correction_semitones() const { return st_; }
private:
    GrainPitch gp_; PitchDetector det_;
    float st_ = 0.f, target_st_ = 0.f, period_ = 0.f, last_note_ = -1.f;
    int hold_ = 0;
};

class VoiceChanger : public Effect {
public:
    VoiceChanger();
    const char* name() const override { return "Voice Changer"; }
    const char* type_id() const override { return "voice_changer"; }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int nf, int nc) override;
private:
    Stft stft_[2]; GrainPitch gp_; PitchDetector det_;
    std::vector<float> env_, tmp_, mag_;
    float formant_warp_ = 1.f, ratio_ = 1.f, period_ = 0.f, robot_phase_ = 0.f;
};

class Denoise : public Effect {
public:
    Denoise();
    const char* name() const override { return "Noise Reduction"; }
    const char* type_id() const override { return "denoise"; }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int nf, int nc) override;
private:
    struct Chan {
        Stft stft;
        std::vector<float> noise, ps, gain;
        int frames = 0;
    };
    Chan ch_[2];
    std::vector<float> g_raw_, g_sm_;
};

} // namespace wavestudio
