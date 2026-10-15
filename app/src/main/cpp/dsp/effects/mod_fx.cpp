#include "mod_fx.h"
#include <algorithm>
#include <cmath>

namespace wavestudio {

namespace { constexpr float kPi = 3.14159265358979f; }

Tremolo::Tremolo() {
    params_ = {
        {"rate", "Rate", 0.1f, 20, 4, 4, "Hz"},
        {"depth", "Depth", 0, 1, 0.5f, 0.5f, ""},
        {"shape", "Shape", 0, 2, 0, 0, "", {"Sine", "Triangle", "Square (soft)"}},
        {"mode", "Mode", 0, 1, 0, 0, "", {"Tremolo", "Auto-pan"}},
        {"stereo", "Stereo phase", 0, 180, 0, 0, "deg"},
    };
}
static float lfo(float ph, int shape) {            // -1..1
    ph -= std::floor(ph);
    if (shape == 1) return ph < 0.5f ? 4.f * ph - 1.f : 3.f - 4.f * ph;
    const float s = std::sin(2.f * kPi * ph);
    if (shape == 2) return std::tanh(6.f * s);
    return s;
}
void Tremolo::process(float** x, int nf, int nc) {
    if (nc < 1) return;
    const float inc = params_[0].value / sample_rate_;
    const float depth = params_[1].value;
    const int shape = (int)std::lround(params_[2].value);
    const bool pan = params_[3].value >= 0.5f;
    const float off = params_[4].value / 360.f;
    for (int i = 0; i < nf; ++i) {
        const float a = lfo(phase_, shape);
        if (pan && nc >= 2) {
            // equal-power auto-pan, depth 1 swings fully left <-> right
            const float p = 0.5f * (1.f + a * depth);                 // 0 left .. 1 right
            const float gl = std::cos(0.5f * kPi * p) * 1.41421356f, gr = std::sin(0.5f * kPi * p) * 1.41421356f;
            const float m = 0.5f * (x[0][i] + x[1][i]);
            x[0][i] = (x[0][i] * 0.5f + m * 0.5f) * gl;
            x[1][i] = (x[1][i] * 0.5f + m * 0.5f) * gr;
        } else {
            const float b = off > 0.f && nc >= 2 ? lfo(phase_ + off, shape) : a;
            x[0][i] *= 1.f - depth * 0.5f * (1.f - a);
            if (nc >= 2) x[1][i] *= 1.f - depth * 0.5f * (1.f - b);
        }
        phase_ += inc; phase_ -= std::floor(phase_);
    }
}

Exciter::Exciter() {
    params_ = {
        {"freq", "Frequency", 1000, 10000, 3500, 3500, "Hz"},
        {"drive", "Drive", 0, 24, 12, 12, "dB"},
        {"amount", "Amount", 0, 1, 0.3f, 0.3f, ""},
    };
}
void Exciter::process(float** x, int nf, int nc) {
    // 2nd-order Butterworth high-pass (transposed DF2, double precision) -> soft saturation -> added back
    const double f = std::min((double)params_[0].value, 0.45 * sample_rate_);
    const double w0 = 2.0 * M_PI * f / sample_rate_, cw = std::cos(w0), al = std::sin(w0) / (2.0 * 0.70710678);
    const double a0 = 1.0 + al, b0 = (1.0 + cw) / 2.0 / a0, b1 = -(1.0 + cw) / a0, b2 = b0;
    const double a1 = -2.0 * cw / a0, a2 = (1.0 - al) / a0;
    const float drive = std::pow(10.f, params_[1].value / 20.f), amt = params_[2].value;
    for (int c = 0; c < std::min(2, nc); ++c) {
        double z1 = st_[c][0], z2 = st_[c][1];
        for (int i = 0; i < nf; ++i) {
            const double in = x[c][i];
            const double hp = b0 * in + z1;
            z1 = b1 * in - a1 * hp + z2; z2 = b2 * in - a2 * hp;
            const float sat = std::tanh(drive * (float)hp);
            x[c][i] += amt * sat * 0.5f;
        }
        st_[c][0] = z1; st_[c][1] = z2;
    }
}

} // namespace wavestudio
