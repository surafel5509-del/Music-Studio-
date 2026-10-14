#pragma once
#include <string>
#include <vector>
#include <cstdint>
#include <cstdio>
#include <memory>
#include "../buffer.h"

namespace wavestudio {

enum class WavError {
    Ok = 0,
    OpenFailed,
    NotRiffWave,
    MissingFmt,
    MissingData,
    UnsupportedFormat,   // e.g. ADPCM, 12-bit
    Truncated,           // header claims more data than the file holds (partial data is still returned)
    TooLarge,            // exceeds caller's memory budget
};

struct WavInfo {
    int channels = 0;
    int64_t frames = 0;
    float sample_rate = 0.f;
    int bit_depth = 0;
    bool is_float = false;
};

class WavReader {
public:
    // Parses the header only (cheap, no audio is decoded).
    static WavError read_info(const std::string& path, WavInfo& info);
    // Decodes the whole file to float. `max_bytes` (0 = unlimited) bounds the decoded size.
    // A file whose header over-states the data size (interrupted recording) yields the
    // frames that are actually present and `err` = Truncated, buffer still non-null.
    static std::shared_ptr<AudioBuffer> load(const std::string& path, WavError* err = nullptr,
                                             size_t max_bytes = 0);
    static bool read_info(const std::string& path, int& channels, int64_t& frames, float& sampleRate, int& bitDepth);
    static const char* error_text(WavError e);
};

class WavWriter {
public:
    static bool save(const std::string& path, const float* const* data, int channels, int64_t frames, float sampleRate, int bitDepth = 24);
    static bool save_interleaved(const std::string& path, const float* data, int channels, int64_t frames, float sampleRate, int bitDepth = 24);
};

} // namespace wavestudio
