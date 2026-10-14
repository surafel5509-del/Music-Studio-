#pragma once
#include "effect.h"
#include <array>
#include <vector>

namespace wavestudio {

// Stereo-linked feed-forward compressor with soft knee, attack/release and makeup gain.
class Compressor : public Effect {
public:
    Compressor();
    const char* name() const override { return "Compressor"; }
    const char* type_id() const override { return "compressor"; }
    void set_sample_rate(float sr) override { sample_rate_ = sr; }
    void reset() override { env_db_ = -120.f; gr_db_ = 0.f; }
    void process(float** buffers, int numFrames, int numChannels) override;
    // Current gain reduction in dB (<= 0), for metering.
    float gain_reduction_db() const { return gr_db_; }
    // Static transfer curve: output level (dB) for an input level (dB). Exposed for tests/UI.
    static float transfer_db(float in_db, float threshold, float ratio, float knee);
private:
    float env_db_ = -120.f;
    float gr_db_ = 0.f;
};

// Look-ahead brickwall limiter: the output never exceeds the ceiling.
class Limiter : public Effect {
public:
    Limiter();
    const char* name() const override { return "Brickwall Limiter"; }
    const char* type_id() const override { return "limiter"; }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int numFrames, int numChannels) override;
private:
    static constexpr int kMaxLook = 2048;       // frames, enough for 10 ms @ 192 kHz
    std::vector<float> delay_[2];
    std::vector<float> hold_;                    // sliding-window max of the look-ahead
    int pos_ = 0;
    float gain_ = 1.f;
};

class NoiseGate : public Effect {
public:
    NoiseGate();
    const char* name() const override { return "Noise Gate"; }
    const char* type_id() const override { return "gate"; }
    void set_sample_rate(float sr) override { sample_rate_ = sr; }
    void reset() override { env_ = 0.f; gain_ = 1.f; open_ = false; hold_ = 0; }
    void process(float** buffers, int numFrames, int numChannels) override;
private:
    float env_ = 0.f, gain_ = 1.f;
    bool open_ = false;
    int hold_ = 0;
};

class DeEsser : public Effect {
public:
    DeEsser();
    const char* name() const override { return "De-Esser"; }
    const char* type_id() const override { return "deesser"; }
    void set_sample_rate(float sr) override { sample_rate_ = sr; }
    void reset() override;
    void process(float** buffers, int numFrames, int numChannels) override;
private:
    struct S { float x1=0,x2=0,y1=0,y2=0; };
    std::array<S,2> hp_{};
    float env_ = 0.f;
};

} // namespace wavestudio
