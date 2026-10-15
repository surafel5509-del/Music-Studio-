#pragma once
#include "effect.h"
#include <vector>
namespace wavestudio {
class Chorus : public Effect {
public:
    Chorus(); const char* name() const override { return "Chorus"; }
    void set_sample_rate(float) override; void reset() override;
    void process(float**,int,int) override;
private:
    struct Dl{std::vector<float> buf;int idx=0;float ph=0;
        void ensure(int s){if((int)buf.size()<s)buf.assign(s,0.f);idx%=std::max(1,(int)buf.size());}
        void write(float v){if(buf.empty())return;buf[idx]=v;idx=(idx+1)%(int)buf.size();}};
    Dl d_[2];
};
class Flanger : public Effect {
public:
    Flanger(); const char* name() const override { return "Flanger"; }
    void set_sample_rate(float) override; void reset() override;
    void process(float**,int,int) override;
private:
    struct Dl{std::vector<float> buf;int idx=0;float ph=0;
        void ensure(int s){if((int)buf.size()<s)buf.assign(s,0.f);idx%=std::max(1,(int)buf.size());}
        void write(float v){if(buf.empty())return;buf[idx]=v;idx=(idx+1)%(int)buf.size();}};
    Dl d_[2];
};
class Phaser : public Effect {
public:
    Phaser(); const char* name() const override { return "Phaser"; }
    void set_sample_rate(float) override; void reset() override;
    void process(float**,int,int) override;
private:
    struct AP{float y1=0,x1=0;float tick(float in,float c){float o=c*(in+y1)-x1;x1=in;y1=o;return o;}};
    AP ap_[2][6]; float ph_=0;
};
}
