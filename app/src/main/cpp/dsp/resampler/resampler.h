#pragma once
#include <cstdint>
#include <memory>
#include <vector>
#include "../buffer.h"

namespace wavestudio {

// Offline band-limited (windowed-sinc, Blackman-Harris) sample-rate conversion of a whole
// buffer. When down-sampling the kernel cutoff is lowered to avoid aliasing. Quality is
// ~96 dB stop-band with 24 zero crossings; it is intended for import/export time, not the
// audio callback. Returns the same shared buffer when no conversion is needed.
std::shared_ptr<AudioBuffer> resample_buffer(const std::shared_ptr<AudioBuffer>& src, float target_rate);

// Interleaved float helper used for export: converts `frames` frames of `channels` channels.
std::vector<float> resample_interleaved(const float* in, int64_t frames, int channels,
                                        float in_rate, float out_rate);

} // namespace wavestudio
