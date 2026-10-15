#pragma once
// Modulation / enhancement effects: tremolo + auto-pan, harmonic exciter.
#include "effect.h"

namespace wavestudio {

class Tremolo : public Effect {
public:
    Tremolo();
    const char* name() const override { return "Tremolo / Auto-Pan"; }
    const char* type_id() const override { return "tremolo"; }
    void reset() override { phase_ = 0.f; }
    void process(float** buffers, int nf, int nc) override;
private:
    float phase_ = 0.f;
};

class Exciter : public Effect {
public:
    Exciter();
    const char* name() const override { return "Harmonic Exciter"; }
    const char* type_id() const override { return "exciter"; }
    void set_sample_rate(float sr) override { sample_rate_ = sr; reset(); }
    void reset() override { for (auto& s : st_) s[0] = s[1] = 0.0; }
    void process(float** buffers, int nf, int nc) override;
private:
    double st_[2][2] = {{0, 0}, {0, 0}};
};

} // namespace wavestudio
