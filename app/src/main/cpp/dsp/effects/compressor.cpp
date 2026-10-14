#include "compressor.h"
#include <cmath>
#include <algorithm>

namespace wavestudio {

static inline float smooth_env(float env, float target, float coeff) {
    return env + (target - env) * coeff;
}

Compressor::Compressor() {
    params_ = {
        {"threshold","Threshold",-60,0,-18,-18,"dB"},
        {"ratio","Ratio",1,20,4,4,":1"},
        {"attack","Attack",0.1,200,5,5,"ms"},
        {"release","Release",10,2000,100,100,"ms"},
        {"knee","Knee",0,24,6,6,"dB"},
        {"makeup","Makeup",0,24,0,0,"dB"},
    };
}

void Compressor::set_sample_rate(float sr) { sample_rate_ = sr; }
void Compressor::reset() { for (auto& c : ch_) c.env = 0.f; }

void Compressor::process(float** buffers, int nf, int nc) {
    float thr = params_[0].value;
    float ratio = params_[1].value;
    float atk = params_[2].value;
    float rel = params_[3].value;
    float knee = params_[4].value;
    float makeup = db_to_linear(params_[5].value);

    float atk_c = 1.f - expf(-1.f / (atk*0.001f*sample_rate_ + 1e-6f));
    float rel_c = 1.f - expf(-1.f / (rel*0.001f*sample_rate_ + 1e-6f));

    for (int c = 0; c < nc; ++c) {
        float* x = buffers[c];
        float env = ch_[c].env;
        for (int i = 0; i < nf; ++i) {
            float in = x[i];
            float level = fabsf(in);
            float level_db = 20.f * log10f(level + 1e-10f);
            // Peak/RMS smooth
            env = smooth_env(env, level, level > env ? atk_c : rel_c);
            float env_db = 20.f * log10f(env + 1e-10f);

            float over = env_db - thr;
            float gr = 0.f;
            if (knee > 0.f) {
                if (over > -knee*0.5f && over < knee*0.5f) {
                    float xn = 2.f * over / knee;
                    float gain_knee = (1.f/ratio - 1.f) * (xn)*(xn) * 0.125f * knee;
                    gr = gain_knee;
                } else if (over >= knee*0.5f) {
                    gr = over * (1.f/ratio - 1.f);
                }
            } else {
                if (over > 0) gr = over*(1.f/ratio - 1.f);
            }
            float g = powf(10.f, gr / 20.f);
            x[i] = in * g * makeup;
        }
        ch_[c].env = env;
    }
}

Limiter::Limiter() {
    params_ = {
        {"ceiling","Ceiling",-6,0,-0.3f,-0.3f,"dB"},
        {"release","Release",1,500,50,50,"ms"},
        {"lookahead","Lookahead",0,10,2,2,"ms"},
    };
}
void Limiter::set_sample_rate(float sr) { sample_rate_ = sr; }
void Limiter::reset() { gain_ = 1.f; peak_hold_ = 0.f; }
void Limiter::process(float** buffers, int nf, int nc) {
    float ceil_lin = db_to_linear(params_[0].value);
    float rel = 1.f - expf(-1.f/(params_[1].value*0.001f*sample_rate_ + 1e-6f));
    for (int c = 0; c < nc; ++c) {
        float* x = buffers[c];
        for (int i = 0; i < nf; ++i) {
            float ab = fabsf(x[i]);
            float target = 1.f;
            if (ab > ceil_lin) target = ceil_lin / ab;
            if (target < gain_) gain_ = target;
            else gain_ = smooth_env(gain_, 1.f, rel);
            x[i] *= gain_;
        }
    }
}

NoiseGate::NoiseGate() {
    params_ = {
        {"threshold","Threshold",-80,-10,-40,-40,"dB"},
        {"attack","Attack",0.1,100,5,5,"ms"},
        {"release","Release",10,2000,200,200,"ms"},
        {"range","Range",-80,0,-60,-60,"dB"},
    };
}
void NoiseGate::set_sample_rate(float sr) { sample_rate_ = sr; }
void NoiseGate::reset() { env_=0.f; open_=false; }
void NoiseGate::process(float** buffers, int nf, int nc) {
    float thr_db = params_[0].value;
    float atk = 1.f - expf(-1.f/(params_[1].value*0.001f*sample_rate_ + 1e-6f));
    float rel = 1.f - expf(-1.f/(params_[2].value*0.001f*sample_rate_ + 1e-6f));
    float range = db_to_linear(params_[3].value);
    for (int c = 0; c < nc; ++c) {
        float* x = buffers[c];
        float env = env_;
        float g = open_ ? 1.f : range;
        for (int i = 0; i < nf; ++i) {
            float a = fabsf(x[i]);
            env = env + (a - env) * (a > env ? atk : rel);
            float db = 20.f*log10f(env+1e-10f);
            if (db > thr_db + 2.f) open_ = true;
            else if (db < thr_db - 2.f) open_ = false;
            float target_g = open_ ? 1.f : range;
            g = g + (target_g - g) * atk;
            x[i] *= g;
        }
        env_ = env;
    }
}

DeEsser::DeEsser() {
    params_ = {
        {"threshold","Threshold",-40,0,-20,-20,"dB"},
        {"freq","Freq",2000,12000,6000,6000,"Hz"},
        {"amount","Amount",0,1,0.3f,0.3f,""},
    };
}
void DeEsser::set_sample_rate(float sr) {
    sample_rate_ = sr;
    // Pre-compute HP coeffs per frequency param change
}
void DeEsser::reset() {
    for (auto& h : hp_) h = {};
    env_ = 0.f;
}
void DeEsser::process(float** buffers, int nf, int nc) {
    float freq = params_[1].value;
    float w0 = 2.f*3.14159265f*freq/sample_rate_;
    float cw = cosf(w0), sw = sinf(w0);
    float alpha = sw/(2.f*1.5f);
    float b0=(1+cw)/2, b1=-(1+cw), b2=(1+cw)/2;
    float a0=1+alpha, a1=-2*cw, a2=1-alpha;
    b0/=a0; b1/=a0; b2/=a0; a1/=a0; a2/=a0;
    float thr = db_to_linear(params_[0].value);
    float amt = params_[2].value;
    for (int c = 0; c < nc; ++c) {
        float* x = buffers[c];
        auto& s = hp_[c];
        float env = env_;
        for (int i = 0; i < nf; ++i) {
            float in = x[i];
            float hpf = b0*in + b1*s.x1 + b2*s.x2 - a1*s.y1 - a2*s.y2;
            s.x2=s.x1; s.x1=in; s.y2=s.y1; s.y1=hpf;
            env = env + (fabsf(hpf)-env) * 0.05f;
            float g = 1.f;
            if (env > thr) g = 1.f - amt * std::min(1.f, (env - thr) / (thr + 1e-6f) * 2.f);
            x[i] = in - hpf * (1.f - g);
        }
        env_ = env;
    }
}

} // namespace wavestudio
