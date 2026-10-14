#pragma once
#include "effect.h"

namespace wavestudio {

// Simple gain + polarity invert + mute-safe trim stage.
class Gain : public Effect {
public:
    Gain();
    const char* name() const override { return "Gain"; }
    const char* type_id() const override { return "gain"; }
    void reset() override { cur_ = target_; }
    void process(float** buffers, int nf, int nc) override;
private:
    float cur_ = 1.f, target_ = 1.f;
};

// Mid/side stereo width: 0 = mono, 1 = unchanged, 2 = twice as wide.
class StereoWidth : public Effect {
public:
    StereoWidth();
    const char* name() const override { return "Stereo Width"; }
    const char* type_id() const override { return "stereo_width"; }
    void process(float** buffers, int nf, int nc) override;
};

} // namespace wavestudio
