#pragma once
#include <vector>
#include <cstdint>
#include "buffer.h"

namespace wavestudio {

class EffectChain;

struct MixerTrack {
    float gain_l = 1.f, gain_r = 1.f;
    bool mute = false;
    bool solo = false;
    const Clip* clip = nullptr;
    EffectChain* chain = nullptr;
    std::shared_ptr<AudioBuffer> recording; // while recording
    int64_t record_offset = 0;
};

class Mixer {
public:
    // Mix all active tracks into output buffer (interleaved stereo float -1..1)
    void mix(float* output, int numFrames, int numChannels, float sampleRate,
             const std::vector<MixerTrack>& tracks, int64_t playhead,
             float masterGainL, float masterGainR, EffectChain* masterChain);
};

} // namespace wavestudio
