#include "reverb.h"
#include <cmath>

namespace wavestudio {

Reverb::Reverb() {
    params_ = {
        {"roomsize","Room Size",0,1,0.7f,0.7f,""},
        {"damp","Damping",0,1,0.5f,0.5f,""},
        {"wet","Wet",0,1,0.3f,0.3f,""},
        {"dry","Dry",0,1,1.f,1.f,""},
        {"width","Width",0,1,1.f,1.f,""},
    };
    set_sample_rate(48000.f);   // never process with unallocated delay lines
}

void Reverb::set_sample_rate(float sr) {
    sample_rate_ = sr;
    // Freeverb-style comb/allpass lengths, scaled by sample rate
    const int combTuningsL[8] = {1557,1617,1491,1422,1277,1356,1188,1116};
    const int apTunings[4] = {225,556,441,341};
    float scale = sr / 44100.f;
    for (int i = 0; i < 8; ++i) comb_[i].resize((int)(combTuningsL[i]*scale));
    for (auto& ch : ap_) for (int i = 0; i < 4; ++i) ch[i].resize((int)(apTunings[i]*scale) + (&ch == &ap_[1] ? 23 : 0));
}

void Reverb::reset() {
    for (auto& c : comb_) { std::fill(c.buf.begin(),c.buf.end(),0.f); c.store=0.f; c.idx=0; }
    for (auto& ch : ap_) for (auto& a : ch) { std::fill(a.buf.begin(),a.buf.end(),0.f); a.idx=0; }
}

void Reverb::process(float** buffers, int nf, int nc) {
    float rs = 0.7f + params_[0].value*0.28f;
    float damp = params_[1].value * 0.4f;
    float wet = params_[2].value;
    float dry = params_[3].value;
    float width = params_[4].value;
    for (auto& c : comb_) { c.feedback = rs; c.damp = damp; }

    for (int c = 0; c < nc; ++c) {
        float* x = buffers[c];
        for (int i = 0; i < nf; ++i) {
            float in = x[i];
            float out = 0.f;
            // Split combs: left 4 for ch 0, right 4 for ch 1
            int base = (c%2)==0 ? 0 : 4;
            for (int k = 0; k < 4; ++k) out += comb_[base+k].tick(in);
            out *= 0.25f;
            for (auto& a : ap_[c & 1]) out = a.tick(out);
            float w1 = wet * (width/2.f + 0.5f);
            float w2 = wet * (1.f - width)/2.f;
            float other = 0.f; // cross-channel handled simply
            x[i] = out*(w1) + in*dry + other*w2;
        }
    }
}

Delay::Delay() {
    params_ = {
        {"time_l","L Time",1,2000,400,400,"ms"},
        {"time_r","R Time",1,2000,500,500,"ms"},
        {"feedback","Feedback",0,0.95f,0.4f,0.4f,""},
        {"mix","Mix",0,1,0.3f,0.3f,""},
    };
}

void Delay::set_sample_rate(float sr) {
    sample_rate_ = sr;
    const int maxFrames = (int)(2.0f * sr) + 2;   // 2000 ms parameter maximum
    for (auto& d : l_) { d.buf.assign(maxFrames, 0.f); d.idx = 0; }
    lp_[0] = lp_[1] = 0.f;
}
void Delay::reset() {
    for (auto& d : l_) { std::fill(d.buf.begin(), d.buf.end(), 0.f); d.idx = 0; }
    lp_[0] = lp_[1] = 0.f;
}
void Delay::process(float** buffers, int nf, int nc) {
    const float fb = std::min(params_[2].value, 0.95f);   // hard stability bound
    const float mix = params_[3].value;
    for (int c = 0; c < std::min(2, nc); ++c) {
        DL& d = l_[c];
        const int size = (int)d.buf.size();
        if (size < 4) continue;
        int delay = (int)(params_[c == 0 ? 0 : 1].value * 0.001f * sample_rate_);
        delay = std::max(1, std::min(size - 1, delay));
        float* x = buffers[c];
        float lp = lp_[c];
        for (int i = 0; i < nf; ++i) {
            int r = d.idx - delay; if (r < 0) r += size;
            const float echo = d.buf[r];
            lp += (echo - lp) * 0.6f;                      // gentle damping in the feedback path
            d.buf[d.idx] = x[i] + lp * fb;
            if (++d.idx >= size) d.idx = 0;
            x[i] = x[i] * (1.f - mix) + echo * mix;
        }
        lp_[c] = lp;
    }
}

} // namespace wavestudio
