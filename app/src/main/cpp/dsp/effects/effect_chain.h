#pragma once
#include "effect.h"
#include <memory>
#include <vector>

namespace wavestudio {

class EffectChain {
public:
    void set_sample_rate(float sr);
    void reset();
    void process_interleaved(float* data, int numFrames, int numChannels, float sr);

    // Plugin/builder
    void add(std::unique_ptr<Effect> e);
    void remove(int index);
    void move(int from, int to);
    void clear();
    int size() const { return (int)effects_.size(); }
    Effect* at(int i) { return effects_[i].get(); }
    const Effect* at(int i) const { return effects_[i].get(); }

    // Enable / disable (bypass)
    void set_bypass(bool b) { bypass_ = b; }
    bool bypass() const { return bypass_; }

    // Wet/dry for send effects
    void set_wet(float w) { wet_ = w; }

private:
    std::vector<std::unique_ptr<Effect>> effects_;
    bool bypass_ = false;
    float wet_ = 1.f;
    float sample_rate_ = 48000.f;
};

} // namespace wavestudio
