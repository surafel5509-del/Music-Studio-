#pragma once
#include "effect.h"
namespace wavestudio {
class DeEsser : public Effect {
public:
    DeEsser(); const char* name() const override { return "De-Esser"; }
    void set_sample_rate(float) override; void reset() override;
    void process(float**,int,int) override;
private: struct BQ{float x1=0,x2=0,y1=0,y2=0;} bq_;
};
}
