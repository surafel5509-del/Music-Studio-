#include "resampler.h"
#include <cmath>
#include <algorithm>
#include <cstring>

namespace wavestudio {

void Resampler::set_ratio(double ratio) { ratio_ = ratio; }
void Resampler::reset() { phase_ = 0.0; history_.clear(); }

int Resampler::process(const float* in, int inFrames, float* out, int outMax, int channels) {
    (void)channels; // assuming interleaved caller; we only provide single-channel resample here
    // Simple linear interpolation with fractional phase for high quality on arbitrary ratios.
    int outCount = 0;
    double pos = phase_;
    for (int o = 0; o < outMax; ++o) {
        int ipos = (int)pos;
        double frac = pos - ipos;
        if (ipos + 1 >= inFrames) break;
        out[o] = (float)(in[ipos]*(1-frac) + in[ipos+1]*frac);
        pos += ratio_;
        outCount++;
    }
    phase_ = pos - (inFrames-1);
    if (phase_ < 0) phase_ = 0;
    return outCount;
}

} // namespace wavestudio
