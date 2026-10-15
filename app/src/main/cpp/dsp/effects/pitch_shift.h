#pragma once
#include "effect.h"
#include <vector>

namespace wavestudio {

// Simple time-domain pitch shifter using two overlapping delay-line grains (SOLA-like)
class PitchShift : public Effect {
public:
    PitchShift();
    const char* name() const override { return "Pitch Shift"; }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int nf, int nc) override;
private:
    struct Delay {
        std::vector<float> buf; int idx=0;
        void ensure(int s){ if((int)buf.size()<s)buf.assign(s,0.f); }
        float read(float d) const;
        void write(float v){ buf[idx]=v; idx=(idx+1)%(int)buf.size();}
    };
    Delay d_[2];
    float phasor_ = 0.f;
};

class TimeStretch {
public:
    // Offline: WSOLA-like. Returns new audio buffer (stereo interleaved) of stretched audio.
    static std::vector<float> stretch(const float* in, int64_t inFrames, int channels,
                                      float ratio, float sampleRate);
};

} // namespace wavestudio
