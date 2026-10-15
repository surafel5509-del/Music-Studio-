#pragma once
#include "effect.h"
#include <array>
#include <vector>

namespace wavestudio {

class Reverb : public Effect {
public:
    Reverb();
    const char* name() const override { return "Reverb (Schroeder)"; }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int nf, int nc) override;
private:
    struct Comb {
        std::vector<float> buf;
        int idx = 0;
        float feedback = 0.8f;
        float damp = 0.f;
        float store = 0.f;
        void resize(int size) { buf.assign(size,0.f); idx=0; store=0.f; }
        float tick(float in) {
            float out = buf[idx];
            store = out*(1.f-damp) + store*damp;
            buf[idx] = in + store*feedback;
            idx = (idx+1) % (int)buf.size();
            return out;
        }
    };
    struct Allpass {
        std::vector<float> buf;
        int idx=0;
        float feedback=0.5f;
        void resize(int s){buf.assign(s,0.f);idx=0;}
        float tick(float in){
            float out = buf[idx];
            buf[idx] = in + out*feedback;
            idx=(idx+1)%(int)buf.size();
            return out - in;
        }
    };
    std::array<Comb,8> comb_;
    std::array<Allpass,4> ap_;
};

class Delay : public Effect {
public:
    Delay();
    const char* name() const override { return "Stereo Delay"; }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int nf, int nc) override;
private:
    struct DL {
        std::vector<float> buf;
        int idx = 0;
        void ensure(int s) { if ((int)buf.size() < s) buf.assign(s,0.f); idx%=std::max(1,(int)buf.size()); }
        float tick(float in, float fb) {
            float out = buf[idx];
            buf[idx] = in + out*fb;
            idx=(idx+1)%(int)buf.size();
            return out;
        }
    };
    std::array<DL,2> l_;
};

} // namespace wavestudio
