#pragma once
#include <vector>

namespace wavestudio {

// ITU-R BS.1770-style K-weighted loudness meter
class LufsMeter {
public:
    struct Biquad { float b0=1,b1=0,b2=0,a1=0,a2=0; float x1=0,x2=0,y1=0,y2=0; float tick(float in); };
    void set_sample_rate(float sr);
    void process(const float** audio, int frames, int channels);
    float integrated() const { return integrated_; }
    float momentary() const { return momentary_; }
    void reset();
private:
    float sample_rate_ = 48000.f;
    struct Channel {
        Biquad hp; Biquad hs;
        float sum_sq = 0;
        double long_sum = 0;
        int64_t long_count = 0;
    };
    std::vector<Channel> ch_;
    float momentary_ = -60.f;
    float integrated_ = -60.f;
    std::vector<float> blocks_;
};

class PhaseMeter {
public:
    void process(const float* L, const float* R, int n);
    float correlation() const { return corr_; }
private:
    float corr_ = 0.f;
};

} // namespace wavestudio
