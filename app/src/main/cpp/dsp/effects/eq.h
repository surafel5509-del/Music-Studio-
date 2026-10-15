#pragma once
#include "effect.h"
#include <array>

namespace wavestudio {

// 8-band parametric EQ with biquad filters per band per channel
class ParametricEQ : public Effect {
public:
    ParametricEQ();
    const char* name() const override { return "Parametric EQ"; }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int numFrames, int numChannels) override;

    enum BandType { LOW_CUT, LOW_SHELF, PEAK, HIGH_SHELF, HIGH_CUT };
    struct Band {
        BandType type = PEAK;
        float freq = 1000.f;
        float gain_db = 0.f;
        float q = 1.f;
        bool enabled = false;
    };

    void set_band(int idx, const Band& b);
    Band get_band(int idx) const;

private:
    void recompute_coeffs();

    struct Biquad {
        float b0=1,b1=0,b2=0,a1=0,a2=0;
        float x1=0,x2=0,y1=0,y2=0;
        void reset(){x1=x2=y1=y2=0;}
        float tick(float in);
    };

    std::array<Band,8> bands_{};
    // biquads per band per channel (stereo: 2 * 8 = 16)
    std::array<std::array<Biquad,8>,4> bqs_{}; // up to 4 channels
    int channels_ = 2;
};

class GraphicEQ : public Effect {
public:
    GraphicEQ();
    const char* name() const override { return "Graphic EQ"; }
    void set_sample_rate(float sr) override;
    void reset() override;
    void process(float** buffers, int numFrames, int numChannels) override;
private:
    ParametricEQ inner_;
};

} // namespace wavestudio
