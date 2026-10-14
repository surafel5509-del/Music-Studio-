#pragma once
#include <vector>
#include <cstdint>
namespace wavestudio {
// Linear / polyphase resampler for arbitrary ratio. Sinc-windowed for good quality.
class Resampler {
public:
    void set_ratio(double ratio);
    int process(const float* in, int inFrames, float* out, int outFramesMax, int channels);
    void reset();
private:
    double ratio_ = 1.0;
    double phase_ = 0.0;
    std::vector<float> history_;
    int history_pos_ = 0;
};
}
