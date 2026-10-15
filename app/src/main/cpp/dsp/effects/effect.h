#pragma once
#include <string>
#include <vector>
#include <cstdint>
#include <cmath>
#include "util/db_utils.h"

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
        // Optional: names of discrete choices (value i selects options[i]); empty for continuous parameters.
        std::vector<std::string> options;
    };

    virtual int param_count() const { return (int)params_.size(); }
    virtual Param& param(int i) { return params_[i]; }
    virtual const Param& param(int i) const { return params_[i]; }
    // Values are clamped to the declared range so automation / UI can never push an
    // effect into an unstable region (e.g. feedback > 1).
    virtual void set_param(int i, float v) {
        if (i < 0 || i >= (int)params_.size()) return;
        Param& p = params_[i];
        if (!(v == v)) return;
        p.value = v < p.min ? p.min : (v > p.max ? p.max : v);
    }
    float value(int i) const { return params_[i].value; }
    // Stable identifier used in project files and presets.
    virtual const char* type_id() const { return "unknown"; }

protected:
    float sample_rate_ = 48000.f;
    std::vector<Param> params_;
};

} // namespace wavestudio
