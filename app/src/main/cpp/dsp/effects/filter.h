#pragma once
#include "effect.h"
namespace wavestudio {
class Filter : public Effect {
public:
    enum Type{LP,HP,BP,NOTCH};
    Filter(); const char* name() const override { return "Filter"; }
    void set_sample_rate(float) override; void reset() override;
    void process(float**,int,int) override;
private:
    struct BQ{float x1=0,x2=0,y1=0,y2=0;} bq_[2];
    float b0=1,b1=0,b2=0,a1=0,a2=0;
    void design(float,float,Type);
};
}
