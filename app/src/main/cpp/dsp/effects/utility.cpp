#include "utility.h"
#include <cmath>

namespace wavestudio {

Gain::Gain() {
    params_ = {
        {"gain","Gain",-60,24,0,0,"dB"},
        {"invert","Phase",0,1,0,0,"", {"Normal","Inverted"}},
    };
}

void Gain::process(float** buf, int nf, int nc) {
    target_ = db_to_linear(params_[0].value) * (params_[1].value >= 0.5f ? -1.f : 1.f);
    // Per-sample ramp over the block avoids zipper noise on parameter changes.
    const float step = (target_ - cur_) / (float)(nf > 0 ? nf : 1);
    float g = cur_;
    for (int i = 0; i < nf; ++i) {
        g += step;
        for (int c = 0; c < nc; ++c) buf[c][i] *= g;
    }
    cur_ = target_;
}

StereoWidth::StereoWidth() {
    params_ = { {"width","Width",0,2,1,1,""} };
}

void StereoWidth::process(float** buf, int nf, int nc) {
    if (nc < 2) return;
    const float w = params_[0].value;
    for (int i = 0; i < nf; ++i) {
        const float m = 0.5f * (buf[0][i] + buf[1][i]);
        const float s = 0.5f * (buf[0][i] - buf[1][i]) * w;
        buf[0][i] = m + s;
        buf[1][i] = m - s;
    }
}

} // namespace wavestudio
