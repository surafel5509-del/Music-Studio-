#pragma once
#include <cstdint>
#include <memory>
#include "buffer.h"

namespace wavestudio {

// Non-destructive clip reference into an AudioBuffer
struct Clip {
    int id = 0;
    int track_id = 0;
    std::shared_ptr<AudioBuffer> buffer;
    int64_t start_frame = 0;
    int64_t offset_frame = 0;
    int64_t length_frames = 0;
    float gain_db = 0.f;
    float fade_in_ms = 0.f;
    float fade_out_ms = 0.f;
    bool reversed = false;
    float pitch_semitones = 0.f;
    float stretch_ratio = 1.f;
};

} // namespace wavestudio
