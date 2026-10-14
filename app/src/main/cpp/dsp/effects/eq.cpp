#include "eq.h"
#include <cmath>
#include <algorithm>

namespace wavestudio {

static const float PI = 3.14159265358979323846f;

float ParametricEQ::Biquad::tick(float in) {
    float out = b0*in + b1*x1 + b2*x2 - a1*y1 - a2*y2;
    x2 = x1; x1 = in; y2 = y1; y1 = out;
    return out;
}

ParametricEQ::ParametricEQ() {
    params_ = {
        {"enable_all","Enable",0,1,1,1,""},
    };
    float freqs[8] = {60,150,300,600,1200,2500,5000,10000};
    for (int i = 0; i < 8; ++i) {
        bands_[i].freq = freqs[i];
        bands_[i].q = 1.4f;
        bands_[i].type = PEAK;
    }
    bands_[0].type = LOW_SHELF;
    bands_[7].type = HIGH_SHELF;
}

void ParametricEQ::set_sample_rate(float sr) {
    if (sr != sample_rate_) { sample_rate_ = sr; recompute_coeffs(); }
}

void ParametricEQ::reset() {
    for (auto& ch : bqs_) for (auto& b : ch) b.reset();
}

void ParametricEQ::set_band(int idx, const Band& b) {
    if (idx<0||idx>=8) return;
    bands_[idx] = b;
    recompute_coeffs();
}

ParametricEQ::Band ParametricEQ::get_band(int idx) const {
    return bands_[idx];
}

void ParametricEQ::recompute_coeffs() {
    for (int i = 0; i < 8; ++i) {
        const Band& b = bands_[i];
        if (!b.enabled) {
            for (auto& ch : bqs_) { ch[i].b0=1; ch[i].b1=ch[i].b2=ch[i].a1=ch[i].a2=0; }
            continue;
        }
        float A = powf(10.f, b.gain_db / 40.f);
        float w0 = 2.f * PI * b.freq / sample_rate_;
        float cosw = cosf(w0), sinw = sinf(w0);
        float alpha = sinw / (2.f * b.q);
        float b0=1,b1=0,b2=0,a0=1,a1=0,a2=0;
        switch (b.type) {
            case PEAK:
                b0 = 1 + alpha*A; b1 = -2*cosw; b2 = 1 - alpha*A;
                a0 = 1 + alpha/A; a1 = -2*cosw; a2 = 1 - alpha/A;
                break;
            case LOW_SHELF: {
                float t = 2*sqrtf(A)*alpha;
                b0 = A*((A+1) - (A-1)*cosw + t);
                b1 = 2*A*((A-1) - (A+1)*cosw);
                b2 = A*((A+1) - (A-1)*cosw - t);
                a0 = (A+1) + (A-1)*cosw + t;
                a1 = -2*((A-1) + (A+1)*cosw);
                a2 = (A+1) + (A-1)*cosw - t;
                break;
            }
            case HIGH_SHELF: {
                float t = 2*sqrtf(A)*alpha;
                b0 = A*((A+1) + (A-1)*cosw + t);
                b1 = -2*A*((A-1) + (A+1)*cosw);
                b2 = A*((A+1) + (A-1)*cosw - t);
                a0 = (A+1) - (A-1)*cosw + t;
                a1 = 2*((A-1) - (A+1)*cosw);
                a2 = (A+1) - (A-1)*cosw - t;
                break;
            }
            case LOW_CUT:
                b0 = (1+cosw)/2; b1 = -(1+cosw); b2 = (1+cosw)/2;
                a0 = 1+alpha; a1 = -2*cosw; a2 = 1-alpha;
                break;
            case HIGH_CUT:
                b0 = (1-cosw)/2; b1 = 1-cosw; b2 = (1-cosw)/2;
                a0 = 1+alpha; a1 = -2*cosw; a2 = 1-alpha;
                break;
        }
        for (auto& ch : bqs_) {
            ch[i].b0=b0/a0; ch[i].b1=b1/a0; ch[i].b2=b2/a0;
            ch[i].a1=a1/a0; ch[i].a2=a2/a0;
        }
    }
}

void ParametricEQ::process(float** buffers, int numFrames, int numChannels) {
    if (params_[0].value < 0.5f) return;
    for (int c = 0; c < numChannels; ++c) {
        float* x = buffers[c];
        for (int n = 0; n < numFrames; ++n) {
            float s = x[n];
            for (int b = 0; b < 8; ++b) {
                s = bqs_[c][b].tick(s);
            }
            x[n] = s;
        }
    }
}

GraphicEQ::GraphicEQ() {
    params_.push_back({"bands","Bands",0,31,31,31,""});
    inner_.set_band(0, {ParametricEQ::LOW_SHELF, 31.f, 0.f, 1.4f, true});
}
void GraphicEQ::set_sample_rate(float sr) { inner_.set_sample_rate(sr); }
void GraphicEQ::reset() { inner_.reset(); }
void GraphicEQ::process(float** buffers, int nf, int nc) { inner_.process(buffers, nf, nc); }

} // namespace wavestudio
