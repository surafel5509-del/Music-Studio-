#include "distortion.h"
#include <cmath>
#include <algorithm>

namespace wavestudio {

Distortion::Distortion() {
    params_ = {
        {"drive","Drive",0,40,12,12,"dB"},
        {"tone","Tone",200,12000,3000,3000,"Hz"},
        {"mix","Mix",0,1,0.5f,0.5f,""},
        {"type","Type",0,4,1,1,"", {"Soft","Hard","Fuzz","Tube","Sine"}},
    };
}

static inline float soft(float x){ return x / (1.f+fabsf(x)); }
static inline float hard(float x){ return std::max(-1.f,std::min(1.f, x)); }
static inline float fuzz(float x){ return (x>0)?1.f:-1.f; }
static inline float tube(float x){ return tanhf(std::max(-20.f, std::min(20.f, x))); }

void Distortion::process(float** buffers, int nf, int nc) {
    const float drive = db_to_linear(params_[0].value);
    const float tone = std::min(params_[1].value, sample_rate_ * 0.45f);
    const float a = 1.f - expf(-2.f * 3.14159265f * tone / sample_rate_);   // post-shaper low-pass
    const float mix = params_[2].value;
    const int type = (int)(params_[3].value + 0.5f);
    for (int c = 0; c < std::min(nc, 2); ++c) {
        float* x = buffers[c];
        float lp = lp_[c];
        for (int i = 0; i < nf; ++i) {
            const float in = x[i];
            const float d = std::max(-1000.f, std::min(1000.f, in * drive));
            float sh;
            switch (type) {
                case 0: sh = soft(d); break;
                case 1: sh = hard(d); break;
                case 2: sh = fuzz(d) * 0.7f; break;
                case 3: sh = tube(d * 0.5f); break;
                case 4: sh = sinf(d); break;
                default: sh = soft(d);
            }
            lp += (sh - lp) * a;
            x[i] = in * (1.f - mix) + lp * mix;
        }
        lp_[c] = lp;
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
    const int bits = std::max(1, std::min(24, (int)(params_[0].value + 0.5f)));
    const float rate = std::max(100.f, params_[1].value);
    const float mix = params_[2].value;
    const float step = powf(2.f, (float)(bits - 1));
    const float stepDiv = 1.f / step;
    const float inc = std::min(1.f, rate / sample_rate_);
    const float start = phasor_;
    float end = start;
    for (int c = 0; c < std::min(nc, 4); ++c) {
        float* x = buffers[c];
        float hold = hold_[c];
        float ph = start;                       // every channel crushes on the same sample grid
        for (int i = 0; i < nf; ++i) {
            ph += inc;
            if (ph >= 1.f) { ph -= 1.f; hold = floorf(x[i] * step + 0.5f) * stepDiv; }
            x[i] = x[i] * (1.f - mix) + hold * mix;
        }
        hold_[c] = hold;
        end = ph;
    }
    phasor_ = end;
}

Filter::Filter() {
    params_ = {
        {"type","Type",0,3,0,0,"", {"Low-pass","High-pass","Band-pass","Notch"}},
        {"freq","Cutoff",20,20000,1000,1000,"Hz"},
        {"res","Resonance",0.1f,20,1.f,1.f,"Q"},
    };
}
void Filter::reset() { for (auto& s : s_) s = {}; }
void Filter::process(float** buffers, int nf, int nc) {
    // Zavalishin TPT state-variable filter: stays stable while the cutoff is modulated.
    const int type = (int)(params_[0].value + 0.5f);
    const double freq = std::min((double)params_[1].value, sample_rate_ * 0.45);
    const double q = params_[2].value;
    const double g = std::tan(3.14159265358979 * freq / sample_rate_);
    const double k = 1.0 / q;
    const double a1 = 1.0 / (1.0 + g * (g + k));
    const double a2 = g * a1;
    const double a3 = g * a2;
    for (int c = 0; c < std::min(nc, 4); ++c) {
        float* x = buffers[c];
        auto& s = s_[c];
        for (int i = 0; i < nf; ++i) {
            const double v0 = x[i];
            const double v3 = v0 - s.ic2;
            const double v1 = a1 * s.ic1 + a2 * v3;
            const double v2 = s.ic2 + a2 * s.ic1 + a3 * v3;
            s.ic1 = 2.0 * v1 - s.ic1;
            s.ic2 = 2.0 * v2 - s.ic2;
            double out;
            switch (type) {
                case 0: out = v2; break;                 // low-pass
                case 1: out = v0 - k * v1 - v2; break;   // high-pass
                case 2: out = v1; break;                 // band-pass (unity peak gain)
                default: out = v0 - k * v1; break;       // notch
            }
            if (!(std::fabs(out) < 1e6)) { s = {}; out = 0; }
            x[i] = (float)out;
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
            params_ = {{"rate","Rate",0.05f,5,0.5f,0.5f,"Hz"},{"depth","Depth",0,1,0.7f,0.7f,""},{"mix","Mix",0,1,0.5f,0.5f,""}};
            break;
    }
    set_sample_rate(48000.f);
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
    const float rate = params_[0].value;
    const float depth = params_[1].value;
    const bool has_fb = params_.size() > 3;
    const float fb = has_fb ? std::min(params_[2].value, 0.9f) : 0.f;
    const float mix = params_.back().value;
    const float start_phase = phase_;
    float end_phase = start_phase;
    for (int c = 0; c < std::min(2, nc); ++c) {
        float* x = buffers[c];
        float phase = start_phase + (c == 1 ? 0.25f : 0.f);      // quarter-cycle offset widens the stereo image
        if (phase >= 1.f) phase -= 1.f;
        float lp = lp_[c];
        for (int i = 0; i < nf; ++i) {
            phase += rate / sample_rate_;
            if (phase >= 1.f) phase -= 1.f;
            const float lfo = sinf(2.f * 3.14159265f * phase);
            const float in = x[i];
            if (k_ == PHASER) {
                float out = in;
                const float freq = 200.f + (lfo * 0.5f + 0.5f) * depth * 1800.f;
                const float t = tanf(3.14159265f * freq / sample_rate_);
                const float alpha = (1.f - t) / (1.f + t);
                for (int s = 0; s < 6; ++s) {
                    const float y = alpha * out + aps_[c][s].zm1;
                    aps_[c][s].zm1 = out - alpha * y;
                    out = y;
                }
                x[i] = in * (1.f - mix) + out * mix;
            } else {
                const float base = (k_ == FLANGER) ? 0.002f * sample_rate_ : 0.015f * sample_rate_;
                const float depthS = (k_ == FLANGER) ? 0.007f * sample_rate_ : 0.010f * sample_rate_;
                const float dly = base + (lfo * 0.5f + 0.5f) * depth * depthS;
                lp += (d_[c].read(dly) - lp) * 0.7f;
                d_[c].write(in + lp * fb);                         // feedback only used by the flanger
                x[i] = in * (1.f - mix) + lp * mix;
            }
        }
        lp_[c] = lp;
        if (c == 0) {
            end_phase = start_phase + (float)nf * rate / sample_rate_;
            end_phase -= std::floor(end_phase);
        }
    }
    phase_ = end_phase;
}

} // namespace wavestudio
