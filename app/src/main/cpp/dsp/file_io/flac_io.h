#pragma once
#include <string>
#include <memory>
#include "../buffer.h"
// FLAC I/O is provided via Android's built-in MediaExtractor on the Kotlin side,
// and decoded PCM is passed to the native engine. These helpers provide stub
// native support that falls back to simple PCM containers when FLAC data is
// already decoded by Kotlin (offline; no external libraries required).
namespace wavestudio {
class FlacIO {
public:
    // Returns nullptr if not raw FLAC; expects pre-decoded PCM from higher layer
    static std::shared_ptr<AudioBuffer> load(const std::string& path);
    static bool save(const std::string& path, const float* const* data, int channels, int64_t frames, float sr, int compression=5);
};
}
