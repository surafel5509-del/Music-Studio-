#include "wav_stream_writer.h"
#include "../engine/rt_utils.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#if defined(__unix__) || defined(__APPLE__) || defined(__ANDROID__)
#include <unistd.h>
#endif

namespace wavestudio {

namespace {
void put16(uint8_t* p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
void put32(uint8_t* p, uint32_t v) { for (int i = 0; i < 4; ++i) p[i] = (uint8_t)(v >> (8 * i)); }
}

bool WavStreamWriter::open(const std::string& path, int channels, int sample_rate, int bit_depth, bool dither) {
    close();
    if (channels < 1 || channels > 32 || sample_rate < 1000) return false;
    if (bit_depth != 16 && bit_depth != 24 && bit_depth != 32) return false;
    f_ = std::fopen(path.c_str(), "wb");
    if (!f_) return false;
    channels_ = channels; sr_ = sample_rate; bits_ = bit_depth; dither_ = dither;
    frames_ = 0; error_ = false;
    uint8_t h[44] = {0};
    std::memcpy(h, "RIFF", 4);
    std::memcpy(h + 8, "WAVEfmt ", 8);
    put32(h + 16, 16);
    put16(h + 20, bit_depth == 32 ? 3 : 1);
    put16(h + 22, (uint16_t)channels);
    put32(h + 24, (uint32_t)sample_rate);
    put32(h + 28, (uint32_t)(sample_rate * channels * bit_depth / 8));
    put16(h + 32, (uint16_t)(channels * bit_depth / 8));
    put16(h + 34, (uint16_t)bit_depth);
    std::memcpy(h + 36, "data", 4);
    if (std::fwrite(h, 1, 44, f_) != 44) { error_ = true; close(); return false; }
    return patch_header();
}

bool WavStreamWriter::patch_header() {
    if (!f_) return false;
    // Sizes saturate at 4 GiB-1 (RIFF limit) rather than wrapping.
    uint64_t data = (uint64_t)frames_ * channels_ * (bits_ / 8);
    uint32_t dsz = data > 0xFFFFFFD0ull ? 0xFFFFFFD0u : (uint32_t)data;
    uint8_t b[4];
    long cur = std::ftell(f_);
    put32(b, dsz + 36);
    bool ok = std::fseek(f_, 4, SEEK_SET) == 0 && std::fwrite(b, 1, 4, f_) == 4;
    put32(b, dsz);
    ok = ok && std::fseek(f_, 40, SEEK_SET) == 0 && std::fwrite(b, 1, 4, f_) == 4;
    ok = ok && std::fseek(f_, cur, SEEK_SET) == 0;
    if (!ok) error_ = true;
    return ok;
}

bool WavStreamWriter::write(const float* in, int64_t frames) {
    if (!f_ || error_) return false;
    const size_t n = (size_t)frames * channels_;
    const int bps = bits_ / 8;
    scratch_.resize(n * bps);
    uint8_t* o = reinterpret_cast<uint8_t*>(&scratch_[0]);
    for (size_t i = 0; i < n; ++i) {
        float v = in[i];
        if (!is_finite_bits(v)) v = 0.f;
        v = std::max(-1.f, std::min(1.f, v));
        if (bits_ == 32) {
            std::memcpy(o + i * 4, &v, 4);
        } else if (bits_ == 16) {
            float d = 0.f;
            if (dither_) {
                rng_ = rng_ * 1664525u + 1013904223u; float a = (rng_ >> 8) * (1.f / 16777216.f);
                rng_ = rng_ * 1664525u + 1013904223u; float b = (rng_ >> 8) * (1.f / 16777216.f);
                d = (a - b) / 32768.f;
            }
            float s = std::max(-1.f, std::min(1.f, v + d)) * 32767.f;
            put16(o + i * 2, (uint16_t)(int16_t)std::lrintf(s));
        } else {
            int32_t s = (int32_t)std::lrintf(v * 8388607.f);
            o[i * 3] = (uint8_t)(s & 0xff);
            o[i * 3 + 1] = (uint8_t)((s >> 8) & 0xff);
            o[i * 3 + 2] = (uint8_t)((s >> 16) & 0xff);
        }
    }
    if (std::fwrite(o, 1, n * bps, f_) != n * bps) { error_ = true; return false; }
    frames_ += frames;
    return true;
}

bool WavStreamWriter::checkpoint() {
    if (!f_) return false;
    bool ok = patch_header() && std::fflush(f_) == 0;
#if defined(__unix__) || defined(__APPLE__) || defined(__ANDROID__)
    if (ok) ok = fsync(fileno(f_)) == 0;
#endif
    if (!ok) error_ = true;
    return ok;
}

bool WavStreamWriter::close() {
    if (!f_) return !error_;
    bool ok = checkpoint();
    if (std::fclose(f_) != 0) ok = false;
    f_ = nullptr;
    return ok && !error_;
}

} // namespace wavestudio
