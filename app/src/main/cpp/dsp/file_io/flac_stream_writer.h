#pragma once
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

namespace wavestudio {

// Self-contained FLAC encoder (no third-party code): lossless, fixed-predictor subframes (orders 0-4),
// constant/verbatim fallbacks, partitioned Rice residual coding, stereo decorrelation (L/R, L/S, R/S, M/S),
// CRC-8/CRC-16 and a STREAMINFO block with the MD5 of the PCM data. Compression is moderate (a bit
// behind libFLAC -5) but the files are standard FLAC and decode with any decoder.
// 16- and 24-bit, 1 or 2 channels. Not real-time safe: use from a worker thread.
class FlacStreamWriter {
public:
    FlacStreamWriter() = default;
    ~FlacStreamWriter() { close(); }
    FlacStreamWriter(const FlacStreamWriter&) = delete;
    FlacStreamWriter& operator=(const FlacStreamWriter&) = delete;

    bool open(const std::string& path, int channels, int sample_rate, int bit_depth, bool dither = false);
    // Interleaved float frames in [-1, 1] (clamped).
    bool write(const float* interleaved, int64_t frames);
    bool close();

    bool is_open() const { return f_ != nullptr; }
    int64_t frames_written() const { return total_; }
    bool had_error() const { return error_; }

private:
    void encode_block(int n);
    bool put(const std::vector<uint8_t>& v);
    FILE* f_ = nullptr;
    int ch_ = 0, sr_ = 0, bits_ = 0;
    bool dither_ = false, error_ = false;
    uint32_t rng_ = 0x2468ace1u;
    static constexpr int kBlock = 4096;
    std::vector<int32_t> pcm_[2];       // pending samples per channel
    int pending_ = 0;
    int64_t total_ = 0;
    uint32_t frame_no_ = 0;
    uint32_t min_frame_ = 0xffffffffu, max_frame_ = 0;
    int min_block_ = 0x7fffffff, max_block_ = 0;
    struct Md5State { uint32_t a = 0x67452301, b = 0xefcdab89, c = 0x98badcfe, d = 0x10325476; uint64_t len = 0; uint8_t buf[64]; size_t fill = 0; } md5_;
    void md5_update(const uint8_t* p, size_t n);
    void md5_final(uint8_t out[16]);
    void md5_block(const uint8_t* p);
};

} // namespace wavestudio
