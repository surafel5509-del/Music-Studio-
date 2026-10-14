#include "mixer.h"
#include "../audio_engine.h"
#include "effects/effect_chain.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace wavestudio {

static inline float apply_fade(float sample, int64_t pos, int64_t len, float fadeIn, float fadeOut, float sr) {
    float fi = fadeIn * sr * 0.001f;
    float fo = fadeOut * sr * 0.001f;
    if (fi > 0.f && pos < (int64_t)fi) {
        sample *= (float)pos / fi;
    }
    if (fo > 0.f && pos > len - (int64_t)fo) {
        int64_t from_end = len - pos;
        sample *= (float)from_end / fo;
    }
    return sample;
}

void Mixer::mix(float* output, int numFrames, int numChannels, float sampleRate,
                const std::vector<MixerTrack>& tracks, int64_t playhead,
                float masterGainL, float masterGainR, EffectChain* masterChain) {
    std::memset(output, 0, sizeof(float) * numFrames * numChannels);

    bool any_solo = false;
    for (size_t ti = 0; ti < tracks.size(); ++ti) {
        if (tracks[ti].solo) { any_solo = true; break; }
    }

    float peak = 0.f;
    float sum_sq = 0.f;

    std::vector<float> tmp(numFrames * numChannels, 0.f);

    for (size_t ti = 0; ti < tracks.size(); ++ti) {
        const MixerTrack& tr = tracks[ti];
        if (tr.mute) continue;
        if (any_solo && !tr.solo) continue;

        std::fill(tmp.begin(), tmp.end(), 0.f);
        float* chbuf = tmp.data();

        // Mix all clips that overlap playhead
        if (tr.clip && tr.clip->buffer) {
            const Clip& c = *tr.clip;
            int64_t abs_end = c.start_frame + c.length_frames;
            if (playhead + numFrames > c.start_frame && playhead < abs_end) {
                int64_t start_offset = std::max<int64_t>(0, c.start_frame - playhead);
                int64_t buf_offset = std::max<int64_t>(0, playhead - c.start_frame) + c.offset_frame;
                int64_t to_read = std::min<int64_t>(numFrames - start_offset, c.length_frames - (buf_offset - c.offset_frame));
                if (to_read > 0) {
                    int ch = std::min<int>(numChannels, c.buffer->channels());
                    const auto& data = c.buffer->data();
                    float clip_gain = db_to_linear(c.gain_db);
                    for (int64_t i = 0; i < to_read; ++i) {
                        int64_t bf = c.reversed ? (c.buffer->frames() - 1 - (buf_offset + i)) : (buf_offset + i);
                        if (bf < 0 || bf >= c.buffer->frames()) continue;
                        float smp = 0.f;
                        for (int k = 0; k < ch; ++k) {
                            smp += data[bf * c.buffer->channels() + k];
                        }
                        smp *= clip_gain / (float)std::max(1, ch);
                        smp = apply_fade(smp, buf_offset - c.offset_frame + i, c.length_frames,
                                         c.fade_in_ms, c.fade_out_ms, sampleRate);
                        int out_idx = (int)(start_offset + i) * numChannels;
                        chbuf[out_idx] = smp * tr.gain_l;
                        if (numChannels > 1) chbuf[out_idx + 1] = smp * tr.gain_r;
                    }
                }
            }
        }

        // Apply track effects
        if (tr.chain) {
            tr.chain->process_interleaved(chbuf, numFrames, numChannels, sampleRate);
        }

        // Sum to output
        for (int i = 0; i < numFrames * numChannels; ++i) {
            output[i] += chbuf[i];
        }
    }

    // Master chain
    if (masterChain) {
        masterChain->process_interleaved(output, numFrames, numChannels, sampleRate);
    }

    // Master gain + metering
    for (int i = 0; i < numFrames; ++i) {
        float l = output[i * numChannels] * masterGainL;
        float r = (numChannels > 1 ? output[i * numChannels + 1] : l) * masterGainR;
        output[i * numChannels] = l;
        if (numChannels > 1) output[i * numChannels + 1] = r;
        peak = std::max(peak, std::max(fabsf(l), fabsf(r)));
        sum_sq += 0.5f * (l * l + r * r);
    }
    // Clamp
    for (int i = 0; i < numFrames * numChannels; ++i) {
        output[i] = std::max(-1.f, std::min(1.f, output[i]));
    }
    (void)peak; (void)sum_sq; // metering is reported by caller via engine atomics
}

} // namespace wavestudio
