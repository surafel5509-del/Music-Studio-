#pragma once
#include "effect.h"
namespace wavestudio {
class NoiseGate : public Effect {
public:
    NoiseGate(); const char* name() const override { return "Noise Gate"; }
    void set_sample_rate(float) override; void reset() override;
    void process(float**,int,int) override;
private: float env_=0;
};
}
