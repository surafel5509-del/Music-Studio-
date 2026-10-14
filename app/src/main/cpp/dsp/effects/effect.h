#pragma once
#include <string>
#include <vector>
#include <cstdint>
#include <cmath>

namespace wavestudio {

class Effect {
public:
    virtual ~Effect() = default;
    virtual const char* name() const = 0;
    virtual void reset() {}
    virtual void set_sample_rate(float sr) { sample_rate_ = sr; }
    virtual void process(float** buffers, int numFrames, int numChannels) = 0;

    struct Param {
        std::string id;
        std::string display;
        float min, max, def, value;
        std::string unit;
    };

    virtual int param_count() const { return (int)params_.size(); }
    virtual Param& param(int i) { return params_[i]; }
    virtual const Param& param(int i) const { return params_[i]; }
    virtual void set_param(int i, float v) {
        if (i >= 0 && i < (int)params_.size()) params_[i].value = v;
    }

protected:
    float sample_rate_ = 48000.f;
    std::vector<Param> params_;
};

} // namespace wavestudio
