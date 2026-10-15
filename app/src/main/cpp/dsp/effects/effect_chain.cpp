#include "effect_chain.h"
#include <cstring>
#include <algorithm>

namespace wavestudio {

void EffectChain::set_sample_rate(float sr) {
    sample_rate_ = sr;
    for (auto& e : effects_) e->set_sample_rate(sr);
}

void EffectChain::reset() {
    for (auto& e : effects_) e->reset();
}

void EffectChain::process_interleaved(float* data, int numFrames, int numChannels, float sr) {
    if (bypass_ || effects_.empty()) return;
    set_sample_rate(sr);

    // Convert interleaved -> planar
    std::vector<float> planar_buf(numFrames * numChannels);
    std::vector<float*> planes(numChannels);
    for (int c = 0; c < numChannels; ++c) {
        planes[c] = planar_buf.data() + c * numFrames;
        for (int i = 0; i < numFrames; ++i) planes[c][i] = data[i * numChannels + c];
    }

    for (auto& e : effects_) e->process(planes.data(), numFrames, numChannels);

    // Convert planar -> interleaved
    for (int c = 0; c < numChannels; ++c) {
        for (int i = 0; i < numFrames; ++i) data[i * numChannels + c] = planes[c][i];
    }
}

void EffectChain::add(std::unique_ptr<Effect> e) {
    e->set_sample_rate(sample_rate_);
    effects_.push_back(std::move(e));
}

void EffectChain::remove(int index) {
    if (index >= 0 && index < (int)effects_.size()) effects_.erase(effects_.begin() + index);
}

void EffectChain::move(int from, int to) {
    if (from == to) return;
    if (from < 0 || from >= (int)effects_.size()) return;
    if (to < 0 || to >= (int)effects_.size()) return;
    auto e = std::move(effects_[from]);
    effects_.erase(effects_.begin() + from);
    effects_.insert(effects_.begin() + to, std::move(e));
}

void EffectChain::clear() { effects_.clear(); }

} // namespace wavestudio
