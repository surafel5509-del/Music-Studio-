#pragma once
#include "effect.h"
#include <array>

namespace wavestudio {

// Feed-forward compressor / expander with knee, attack, release, makeup gain
class Compressor : public Effect {
public:
    Compressor();
    const char* name() const override { return "Compressor"; }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int numFrames, int numChannels) override;
    struct Channel {
        float env = 0.f;
    };
private:
    std::array<Channel,4> ch_{};
};

class Limiter : public Effect {
public:
    Limiter();
    const char* name() const override { return "Brickwall Limiter"; }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int numFrames, int numChannels) override;
private:
    float peak_hold_ = 0.f;
    float gain_ = 1.f;
};

class NoiseGate : public Effect {
public:
    NoiseGate();
    const char* name() const override { return "Noise Gate"; }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int numFrames, int numChannels) override;
private:
    float env_ = 0.f;
    bool open_ = false;
};

class DeEsser : public Effect {
public:
    DeEsser();
    const char* name() const override { return "De-Esser"; }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int numFrames, int numChannels) override;
private:
    struct S { float x1=0,x2=0,y1=0,y2=0; };
    std::array<S,4> hp_{};
    float env_ = 0.f;
};

} // namespace wavestudio
