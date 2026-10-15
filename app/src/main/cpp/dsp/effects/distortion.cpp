#include "distortion.h"
#include <cmath>
#include <algorithm>

namespace wavestudio {

Distortion::Distortion() {
    params_ = {
        {"drive","Drive",1,100,20,20,"dB"},
        {"tone","Tone",200,12000,3000,3000,"Hz"},
        {"mix","Mix",0,1,0.5f,0.5f,""},
        {"type","Type",0,4,1,1,""}, // 0=soft,1=hard,2=fuzz,3=tube,4=sine
    };
}

static inline float soft(float x){ return x / (1.f+fabsf(x)); }
static inline float hard(float x){ return std::max(-1.f,std::min(1.f, x)); }
static inline float fuzz(float x){ return (x>0)?1.f:-1.f; }
static inline float tube(float x){ float a=expf(x); return (a - 1.f/a)/(a + 1.f/a); }

void Distortion::process(float** buffers, int nf, int nc) {
    float drive = db_to_linear(params_[0].value);
    float mix = params_[2].value;
    int type = (int)params_[3].value;
    for (int c = 0; c < nc; ++c) {
        float* x = buffers[c];
        for (int i = 0; i < nf; ++i) {
            float in = x[i];
            float d = in * drive;
            float sh;
            switch(type){
                case 0: sh = soft(d); break;
                case 1: sh = hard(d); break;
                case 2: sh = fuzz(d); break;
                case 3: sh = tube(d*0.5f); break;
                case 4: sh = sinf(d); break;
                default: sh = soft(d);
            }
            x[i] = in*(1.f-mix) + sh*mix;
        }
    }
}

BitCrusher::BitCrusher() {
    params_ = {
        {"bits","Bit Depth",1,32,8,8,"bits"},
        {"rate","Sample Rate",100,48000,8000,8000,"Hz"},
        {"mix","Mix",0,1,0.5f,0.5f,""},
    };
}

void BitCrusher::process(float** buffers, int nf, int nc) {
    int bits = (int)params_[0].value;
    float rate = params_[1].value;
    float mix = params_[2].value;
    float step = powf(2.f, (float)(bits-1));
    float stepDiv = 1.f/step;
    float phasor = phasor_;
    for (int c = 0; c < nc; ++c) {
        float* x = buffers[c];
        float hold = hold_[c];
        float ph = phasor;
        for (int i = 0; i < nf; ++i) {
            ph += rate / sample_rate_;
            if (ph >= 1.f) {
                ph -= 1.f;
                float q = floorf(x[i]*step + 0.5f) * stepDiv;
                hold = q;
            }
            x[i] = x[i]*(1.f-mix) + hold*mix;
        }
        hold_[c] = hold;
    }
    phasor_ = 0;
}

Filter::Filter() {
    params_ = {
        {"type","Type (0=LP,1=HP,2=BP,3=N)",0,3,0,0,""},
        {"freq","Cutoff",20,20000,1000,1000,"Hz"},
        {"res","Resonance",0.1f,20,1.f,1.f,"Q"},
    };
}
void Filter::reset() { for (auto& s : s_) s = {}; }
void Filter::process(float** buffers, int nf, int nc) {
    int type = (int)params_[0].value;
    float freq = params_[1].value;
    float q = params_[2].value;
    float w0 = 2.f*3.14159265f*freq/sample_rate_;
    float cw=cosf(w0), sw=sinf(w0);
    float alpha = sw/(2.f*q);
    float b0=1,b1=0,b2=0,a0=1,a1=0,a2=0;
    switch(type){
        case 0: // LP
            b0=(1-cw)/2; b1=1-cw; b2=(1-cw)/2;
            a0=1+alpha; a1=-2*cw; a2=1-alpha; break;
        case 1: // HP
            b0=(1+cw)/2; b1=-(1+cw); b2=(1+cw)/2;
            a0=1+alpha; a1=-2*cw; a2=1-alpha; break;
        case 2: // BP
            b0=alpha; b1=0; b2=-alpha; a0=1+alpha; a1=-2*cw; a2=1-alpha; break;
        case 3: // NOTCH
            b0=1; b1=-2*cw; b2=1; a0=1+alpha; a1=-2*cw; a2=1-alpha; break;
    }
    b0/=a0; b1/=a0; b2/=a0; a1/=a0; a2/=a0;
    for (int c = 0; c < nc; ++c) {
        float* x = buffers[c];
        auto& s = s_[c];
        for (int i = 0; i < nf; ++i) {
            float in = x[i];
            float out = b0*in + b1*s.x1 + b2*s.x2 - a1*s.y1 - a2*s.y2;
            s.x2=s.x1; s.x1=in; s.y2=s.y1; s.y1=out;
            x[i] = out;
        }
    }
}

ChorusFlangerPhaser::ChorusFlangerPhaser(Kind k) : k_(k) {
    switch(k){
        case CHORUS:
            params_ = {{"rate","Rate",0.05f,10,1.f,1.f,"Hz"},{"depth","Depth",0,1,0.5f,0.5f,""},{"mix","Mix",0,1,0.5f,0.5f,""}};
            break;
        case FLANGER:
            params_ = {{"rate","Rate",0.05f,5,0.5f,0.5f,"Hz"},{"depth","Depth",0,1,0.7f,0.7f,""},{"feedback","FB",0,0.9f,0.5f,0.5f,""},{"mix","Mix",0,1,0.5f,0.5f,""}};
            break;
        case PHASER:
            params_ = {{"rate","Rate",0.05f,5,0.5f,0.5f,"Hz"},{"depth","Depth",0,1,0.7f,0.7f,""},{"feedback","FB",0,0.9f,0.f,0.f,""},{"mix","Mix",0,1,0.5f,0.5f,""}};
            break;
    }
}

void ChorusFlangerPhaser::set_sample_rate(float sr) {
    sample_rate_ = sr;
    d_[0].ensure((int)(sr*0.05f));
    d_[1].ensure((int)(sr*0.05f));
}
void ChorusFlangerPhaser::reset() {
    phase_=0.f;
    for (auto& d:d_){std::fill(d.buf.begin(),d.buf.end(),0.f); d.idx=0;}
    for (auto& c:aps_) for (auto&a:c) a.zm1=0;
}

float ChorusFlangerPhaser::DelayL::read(float delaySamples) {
    float d = delaySamples;
    int id = (int)d;
    float f = d - id;
    int sz = (int)buf.size();
    float a = buf[(idx - 1 - id + sz*2)%sz];
    float b = buf[(idx - 2 - id + sz*2)%sz];
    return a + (b-a)*f;
}

void ChorusFlangerPhaser::process(float** buffers, int nf, int nc) {
    float rate = params_[0].value;
    float depth = params_[1].value;
    float fb = (params_.size()>2) ? params_[2].value : 0.f;
    float mix = params_.back().value;
    float phase = phase_;
    for (int c = 0; c < std::min(2,nc); ++c) {
        float* x = buffers[c];
        for (int i = 0; i < nf; ++i) {
            phase += rate / sample_rate_;
            if (phase > 1.f) phase -= 1.f;
            float lfo = sinf(2.f*3.14159265f*phase);
            float in = x[i];
            if (k_ == PHASER) {
                // 6-stage allpass
                float out = in;
                float freq = 200.f + (lfo*0.5f+0.5f)*depth*1800.f;
                float w0 = 2.f*3.14159265f*freq/sample_rate_;
                float alpha = (1.f - tanf(w0/2.f)) / (1.f + tanf(w0/2.f));
                for (int s=0; s<6;++s){
                    float y = alpha*out + aps_[c][s].zm1;
                    aps_[c][s].zm1 = y - alpha*out;
                    out = y;
                }
                x[i] = in*(1.f-mix) + (in+out*fb)*mix - out*mix;
            } else {
                float base = (k_==FLANGER)?0.002f*sample_rate_ : 0.015f*sample_rate_;
                float depthS = (k_==FLANGER)?0.007f*sample_rate_:0.020f*sample_rate_;
                float dly = base + (lfo*0.5f+0.5f)*depth*depthS;
                d_[c].write(in);
                float del = d_[c].read(dly);
                x[i] = in*(1.f-mix) + del*mix;
            }
        }
    }
    phase_ = phase;
}

} // namespace wavestudio
