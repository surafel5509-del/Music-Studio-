#pragma once
#include "effect.h"
namespace wavestudio {
class Limiter : public Effect {
public:
    Limiter(); const char* name() const override { return "Limiter"; }
    void set_sample_rate(float) override; void reset() override;
    void process(float**,int,int) override;
private: float peak_=0;
};
}
