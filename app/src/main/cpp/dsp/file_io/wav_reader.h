#pragma once
#include <string>
#include <vector>
#include <cstdint>
#include <memory>
#include "../buffer.h"

namespace wavestudio {

class WavReader {
public:
    static std::shared_ptr<AudioBuffer> load(const std::string& path);
    static bool read_info(const std::string& path, int& channels, int64_t& frames, float& sampleRate, int& bitDepth);
};

class WavWriter {
public:
    static bool save(const std::string& path, const float* const* data, int channels, int64_t frames, float sampleRate, int bitDepth = 24);
    static bool save_interleaved(const std::string& path, const float* data, int channels, int64_t frames, float sampleRate, int bitDepth = 24);
};

} // namespace wavestudio
