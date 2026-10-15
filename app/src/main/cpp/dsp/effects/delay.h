#pragma once
#include "effect.h"
#include <vector>
namespace wavestudio {
class Delay : public Effect {
public:
    Delay();
    const char* name() const override { return "Delay"; }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int nf, int nc) override;
private:
    struct Dline { std::vector<float> buf; int idx=0; void ensure(int s){ if((int)buf.size()<s)buf.assign(s,0.f); idx%=std::max(1,(int)buf.size());}
                void write(float v){ if(buf.empty())return; buf[idx]=v; idx=(idx+1)%(int)buf.size();}
                };
    Dline dl_[2];
};
}
