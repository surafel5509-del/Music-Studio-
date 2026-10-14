#include "eq.h"
#include <string>
#include <algorithm>
#include <cmath>
#include <algorithm>

namespace wavestudio {

static const float PI = 3.14159265358979323846f;

float ParametricEQ::Biquad::tick(float in) {
    const double out = b0 * in + z1;
    z1 = b1 * in - a1 * out + z2;
    z2 = b2 * in - a2 * out;
    if (!(std::fabs(out) < 1e6)) { z1 = z2 = 0; return 0.f; }   // unstable/NaN: self-heal
    return (float)out;
}

ParametricEQ::ParametricEQ() {
    // Parameter layout: [0] enable, then for each of the 8 bands: gain, freq, Q.
    params_.push_back({"enable_all","Enable",0,1,1,1,""});
    const float freqs[8] = {60,150,300,600,1200,2500,5000,10000};
    for (int i = 0; i < 8; ++i) {
        bands_[i].freq = freqs[i];
        bands_[i].q = 1.4f;
        bands_[i].type = PEAK;
        bands_[i].enabled = true;      // a 0 dB band is transparent
        const std::string n = std::to_string(i + 1);
        params_.push_back({"b" + n + "_gain", "Band " + n + " Gain", -24, 24, 0, 0, "dB"});
        params_.push_back({"b" + n + "_freq", "Band " + n + " Freq", 20, 20000, freqs[i], freqs[i], "Hz"});
        params_.push_back({"b" + n + "_q", "Band " + n + " Q", 0.1f, 10, 1.4f, 1.4f, ""});
    }
    bands_[0].type = LOW_SHELF;
    bands_[7].type = HIGH_SHELF;
    recompute_coeffs();
}

void ParametricEQ::set_param(int i, float v) {
    Effect::set_param(i, v);
    if (i < 1 || i > 24) return;
    const int band = (i - 1) / 3, kind = (i - 1) % 3;
    const float val = params_[i].value;
    if (kind == 0) bands_[band].gain_db = val;
    else if (kind == 1) bands_[band].freq = std::min(val, sample_rate_ * 0.45f);
    else bands_[band].q = val;
    recompute_coeffs();
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
    params_[1+idx*3].value = b.gain_db;
    params_[2+idx*3].value = b.freq;
    params_[3+idx*3].value = b.q;
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
        double A = pow(10.0, b.gain_db / 40.f);
        double w0 = 2.f * PI * b.freq / sample_rate_;
        double cosw = cos(w0), sinw = sin(w0);
        double alpha = sinw / (2.f * b.q);
        double b0=1,b1=0,b2=0,a0=1,a1=0,a2=0;
        switch (b.type) {
            case PEAK:
                b0 = 1 + alpha*A; b1 = -2*cosw; b2 = 1 - alpha*A;
                a0 = 1 + alpha/A; a1 = -2*cosw; a2 = 1 - alpha/A;
                break;
            case LOW_SHELF: {
                double t = 2*sqrt(A)*alpha;
                b0 = A*((A+1) - (A-1)*cosw + t);
                b1 = 2*A*((A-1) - (A+1)*cosw);
                b2 = A*((A+1) - (A-1)*cosw - t);
                a0 = (A+1) + (A-1)*cosw + t;
                a1 = -2*((A-1) + (A+1)*cosw);
                a2 = (A+1) + (A-1)*cosw - t;
                break;
            }
            case HIGH_SHELF: {
                double t = 2*sqrt(A)*alpha;
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

} // namespace wavestudio
