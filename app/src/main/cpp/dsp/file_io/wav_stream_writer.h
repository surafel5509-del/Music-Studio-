#pragma once
#include <cstdint>
#include <cstdio>
#include <string>

namespace wavestudio {

// Incremental WAV writer. The RIFF/data sizes are patched on close() and also on
// checkpoint(), so a recording that is interrupted (crash, kill, power loss) after a
// checkpoint is still a valid WAV file containing everything written up to that point.
// Not real-time safe: use from a worker thread only.
class WavStreamWriter {
public:
    WavStreamWriter() = default;
    ~WavStreamWriter() { close(); }
    WavStreamWriter(const WavStreamWriter&) = delete;
    WavStreamWriter& operator=(const WavStreamWriter&) = delete;

    // bit_depth: 16, 24 or 32 (32 = IEEE float). 16-bit output uses TPDF dither when `dither` is set.
    bool open(const std::string& path, int channels, int sample_rate, int bit_depth, bool dither = false);
    // Interleaved float frames in [-1, 1] (clamped). Returns false on I/O error (disk full...).
    bool write(const float* interleaved, int64_t frames);
    // Patch headers and flush to storage.
    bool checkpoint();
    bool close();

    bool is_open() const { return f_ != nullptr; }
    int64_t frames_written() const { return frames_; }
    bool had_error() const { return error_; }

private:
    bool patch_header();
    FILE* f_ = nullptr;
    int channels_ = 0, sr_ = 0, bits_ = 0;
    bool dither_ = false;
    int64_t frames_ = 0;
    bool error_ = false;
    uint32_t rng_ = 0x12345678u;
    std::string scratch_;
};

} // namespace wavestudio
