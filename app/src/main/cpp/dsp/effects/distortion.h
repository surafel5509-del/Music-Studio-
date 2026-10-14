#pragma once
#include "effect.h"

namespace wavestudio {
class Distortion : public Effect {
public:
    Distortion();
    const char* name() const override { return "Distortion"; }
    const char* type_id() const override { return "distortion"; }
    void reset() override { lp_[0] = lp_[1] = 0.f; }
    void process(float** buffers, int nf, int nc) override;
private:
    float lp_[2] = {0.f, 0.f};
};

class BitCrusher : public Effect {
public:
    BitCrusher();
    const char* name() const override { return "Bit Crusher"; }
    const char* type_id() const override { return "bitcrusher"; }
    void process(float** buffers, int nf, int nc) override;
private:
    float phasor_ = 0.f;
    float hold_[4] = {0,0,0,0};
};

class Filter : public Effect {
public:
    enum Type { LP, HP, BP, NOTCH };
    Filter();
    const char* name() const override { return "Filter"; }
    const char* type_id() const override { return "filter"; }
    void reset() override;
    void process(float** buffers, int nf, int nc) override;
private:
    struct S { double ic1=0, ic2=0; };   // TPT state-variable filter integrator states
    S s_[4];
};

class ChorusFlangerPhaser : public Effect {
public:
    enum Kind { CHORUS, FLANGER, PHASER };
    ChorusFlangerPhaser(Kind k);
    const char* type_id() const override {
        switch(k_){ case CHORUS: return "chorus"; case FLANGER: return "flanger"; case PHASER: return "phaser"; }
        return "mod";
    }
    const char* name() const override {
        switch(k_){
            case CHORUS: return "Chorus";
            case FLANGER: return "Flanger";
            case PHASER: return "Phaser";
        }
        return "Mod";
    }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int nf, int nc) override;
private:
    Kind k_;
    struct DelayL {
        std::vector<float> buf; int idx=0;
        void ensure(int s){ if((int)buf.size()<s)buf.assign(s,0.f); }
        float read(float delaySamples);
        void write(float in){ buf[idx]=in; idx=(idx+1)%(int)buf.size(); }
    } d_[2];
    float phase_ = 0.f;
    float lp_[2] = {0.f, 0.f};
    struct AP { float zm1=0; } aps_[4][6];
};
}
