#include "wav_reader.h"
#include "wav_stream_writer.h"
#include "../engine/rt_utils.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace wavestudio {

namespace {

struct File {
    FILE* f = nullptr;
    explicit File(const std::string& p, const char* mode) { f = std::fopen(p.c_str(), mode); }
    ~File() { if (f) std::fclose(f); }
    File(const File&) = delete;
    File& operator=(const File&) = delete;
};

uint16_t rd16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }
uint32_t rd32(const uint8_t* p) { return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24); }

struct Parsed {
    WavInfo info;
    int64_t data_pos = 0;
    int64_t data_bytes = 0;   // bytes actually available
    bool truncated = false;
};

int64_t file_size(FILE* f) {
    long cur = std::ftell(f);
    std::fseek(f, 0, SEEK_END);
    long end = std::ftell(f);
    std::fseek(f, cur, SEEK_SET);
    return end < 0 ? 0 : end;
}

WavError parse(FILE* f, Parsed& out) {
    int64_t fsz = file_size(f);
    uint8_t hdr[12];
    if (std::fread(hdr, 1, 12, f) != 12) return WavError::NotRiffWave;
    if (std::memcmp(hdr, "RIFF", 4) != 0 || std::memcmp(hdr + 8, "WAVE", 4) != 0) return WavError::NotRiffWave;

    bool have_fmt = false, have_data = false;
    int fmt_tag = 0;
    int64_t pos = 12;
    while (pos + 8 <= fsz) {
        uint8_t ch[8];
        std::fseek(f, (long)pos, SEEK_SET);
        if (std::fread(ch, 1, 8, f) != 8) break;
        uint32_t csz = rd32(ch + 4);
        int64_t body = pos + 8;
        if (std::memcmp(ch, "fmt ", 4) == 0) {
            if (csz < 16 || body + 16 > fsz) return WavError::MissingFmt;
            uint8_t fm[40] = {0};
            size_t want = std::min<uint32_t>(csz, sizeof(fm));
            if (std::fread(fm, 1, want, f) != want) return WavError::MissingFmt;
            fmt_tag = rd16(fm);
            out.info.channels = rd16(fm + 2);
            out.info.sample_rate = (float)rd32(fm + 4);
            out.info.bit_depth = rd16(fm + 14);
            if (fmt_tag == 0xFFFE && csz >= 40) fmt_tag = rd16(fm + 24); // WAVE_FORMAT_EXTENSIBLE sub-format
            out.info.is_float = (fmt_tag == 3);
            have_fmt = true;
        } else if (std::memcmp(ch, "data", 4) == 0) {
            out.data_pos = body;
            int64_t avail = fsz - body;
            int64_t claimed = csz;
            // 0 or 0xFFFFFFFF: streaming writer that never finalised the header
            if (csz == 0 || csz == 0xFFFFFFFFu || claimed > avail) {
                out.data_bytes = avail;
                out.truncated = (csz != 0 && csz != 0xFFFFFFFFu) || csz == 0xFFFFFFFFu || csz == 0;
                if (csz == 0 && avail == 0) out.truncated = false;
            } else {
                out.data_bytes = claimed;
            }
            have_data = true;
            break; // fmt precedes data in every writer we care about
        }
        pos = body + (int64_t)csz + (csz & 1);
    }
    if (!have_fmt) return WavError::MissingFmt;
    if (!have_data) return WavError::MissingData;

    int bd = out.info.bit_depth;
    bool ok = out.info.channels >= 1 && out.info.channels <= 32 && out.info.sample_rate >= 1000.f;
    if (fmt_tag == 1) ok = ok && (bd == 8 || bd == 16 || bd == 24 || bd == 32);
    else if (fmt_tag == 3) ok = ok && (bd == 32 || bd == 64);
    else ok = false;
    if (!ok) return WavError::UnsupportedFormat;

    int bps = bd / 8;
    out.info.frames = out.data_bytes / (int64_t)(bps * out.info.channels);
    return WavError::Ok;
}

inline float decode(const uint8_t* p, int bd, bool is_float) {
    switch (bd) {
        case 8: return ((int)p[0] - 128) / 128.f;
        case 16: { int16_t s = (int16_t)rd16(p); return s / 32768.f; }
        case 24: {
            int32_t s = (int32_t)((uint32_t)p[0] << 8 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 24);
            return (float)(s >> 8) / 8388608.f;
        }
        case 32: {
            if (is_float) { float v; std::memcpy(&v, p, 4); return v; }
            int32_t s = (int32_t)rd32(p);
            return (float)((double)s / 2147483648.0);
        }
        case 64: { double v; std::memcpy(&v, p, 8); return (float)v; }
    }
    return 0.f;
}

} // namespace

const char* WavReader::error_text(WavError e) {
    switch (e) {
        case WavError::Ok: return "ok";
        case WavError::OpenFailed: return "cannot open file";
        case WavError::NotRiffWave: return "not a RIFF/WAVE file";
        case WavError::MissingFmt: return "missing or invalid fmt chunk";
        case WavError::MissingData: return "missing data chunk";
        case WavError::UnsupportedFormat: return "unsupported WAV encoding";
        case WavError::Truncated: return "file truncated (partial audio recovered)";
        case WavError::TooLarge: return "file too large for available memory";
    }
    return "unknown";
}

WavError WavReader::read_info(const std::string& path, WavInfo& info) {
    File f(path, "rb");
    if (!f.f) return WavError::OpenFailed;
    Parsed p;
    WavError e = parse(f.f, p);
    if (e == WavError::Ok) info = p.info;
    return e;
}

bool WavReader::read_info(const std::string& path, int& channels, int64_t& frames, float& sampleRate, int& bitDepth) {
    WavInfo i;
    if (read_info(path, i) != WavError::Ok) return false;
    channels = i.channels; frames = i.frames; sampleRate = i.sample_rate; bitDepth = i.bit_depth;
    return true;
}

std::shared_ptr<AudioBuffer> WavReader::load(const std::string& path, WavError* err, size_t max_bytes) {
    auto fail = [&](WavError e) { if (err) *err = e; return std::shared_ptr<AudioBuffer>(); };
    File f(path, "rb");
    if (!f.f) return fail(WavError::OpenFailed);
    Parsed p;
    WavError e = parse(f.f, p);
    if (e != WavError::Ok) return fail(e);
    if (p.info.frames <= 0) return fail(WavError::MissingData);
    size_t need = (size_t)p.info.frames * (size_t)p.info.channels * sizeof(float);
    if (max_bytes && need > max_bytes) return fail(WavError::TooLarge);

    auto buf = std::make_shared<AudioBuffer>();
    buf->allocate(p.info.channels, p.info.frames, p.info.sample_rate);
    float* out = buf->data_mut().data();

    const int bd = p.info.bit_depth;
    const int bps = bd / 8;
    const int ch = p.info.channels;
    const size_t frame_bytes = (size_t)bps * ch;
    std::fseek(f.f, (long)p.data_pos, SEEK_SET);

    std::vector<uint8_t> chunk(frame_bytes * 4096);
    int64_t done = 0;
    while (done < p.info.frames) {
        size_t want = (size_t)std::min<int64_t>(4096, p.info.frames - done);
        size_t got = std::fread(chunk.data(), frame_bytes, want, f.f);
        if (got == 0) break;
        for (size_t i = 0; i < got; ++i)
            for (int c = 0; c < ch; ++c) {
                float v = decode(chunk.data() + i * frame_bytes + (size_t)c * bps, bd, p.info.is_float);
                if (!is_finite_bits(v)) v = 0.f;
                out[(done + (int64_t)i) * ch + c] = std::max(-1.f, std::min(1.f, v));
            }
        done += (int64_t)got;
        if (got < want) break;
    }
    if (done < p.info.frames) {
        // fewer frames than we sized for: shrink and flag
        buf->resize_frames(done);
        p.truncated = true;
    }
    if (buf->frames() <= 0) return fail(WavError::MissingData);
    buf->build_peak_table();
    if (err) *err = p.truncated ? WavError::Truncated : WavError::Ok;
    return buf;
}

bool WavWriter::save_interleaved(const std::string& path, const float* data, int channels, int64_t frames, float sampleRate, int bitDepth) {
    WavStreamWriter w;
    if (!w.open(path, channels, (int)sampleRate, bitDepth)) return false;
    if (!w.write(data, frames)) { w.close(); return false; }
    return w.close();
}

bool WavWriter::save(const std::string& path, const float* const* data, int channels, int64_t frames, float sampleRate, int bitDepth) {
    WavStreamWriter w;
    if (!w.open(path, channels, (int)sampleRate, bitDepth)) return false;
    std::vector<float> blk(4096 * (size_t)channels);
    for (int64_t pos = 0; pos < frames; pos += 4096) {
        int64_t n = std::min<int64_t>(4096, frames - pos);
        for (int64_t i = 0; i < n; ++i)
            for (int c = 0; c < channels; ++c) blk[(size_t)(i * channels + c)] = data[c][pos + i];
        if (!w.write(blk.data(), n)) { w.close(); return false; }
    }
    return w.close();
}

} // namespace wavestudio
