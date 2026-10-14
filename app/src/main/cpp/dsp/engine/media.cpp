#include "media.h"
#include "../resampler/resampler.h"
#include <new>

namespace wavestudio {

int MediaLibrary::map_error(WavError e) {
    switch (e) {
        case WavError::Ok: return kMediaOk;
        case WavError::OpenFailed: return kMediaOpenFailed;
        case WavError::NotRiffWave: case WavError::MissingFmt: case WavError::MissingData: return kMediaNotWav;
        case WavError::UnsupportedFormat: return kMediaUnsupported;
        case WavError::Truncated: return kMediaTruncated;
        case WavError::TooLarge: return kMediaTooLarge;
    }
    return kMediaNotWav;
}

size_t MediaLibrary::bytes_used_locked() const {
    size_t n = 0;
    for (auto& kv : cache_)
        if (kv.second.buf) n += (size_t)kv.second.buf->frames() * kv.second.buf->channels() * sizeof(float);
    return n;
}
size_t MediaLibrary::bytes_used() const { std::lock_guard<std::mutex> l(m_); return bytes_used_locked(); }

void MediaLibrary::trim() {
    std::lock_guard<std::mutex> l(m_);
    for (auto it = cache_.begin(); it != cache_.end();) {
        if (!it->second.buf || it->second.buf.use_count() == 1) it = cache_.erase(it); else ++it;
    }
}

void MediaLibrary::invalidate(const std::string& path) {
    std::lock_guard<std::mutex> l(m_);
    const std::string prefix = path + "|";
    for (auto it = cache_.begin(); it != cache_.end();) {
        if (it->first.compare(0, prefix.size(), prefix) == 0) it = cache_.erase(it); else ++it;
    }
}

bool MediaLibrary::info(const std::string& path, WavInfo& out, int* error) {
    WavError e = WavReader::read_info(path, out);
    if (error) *error = map_error(e);
    return e == WavError::Ok || e == WavError::Truncated;
}

MediaLibrary::Result MediaLibrary::get(const std::string& path, float target_rate) {
    Result r;
    const std::string key = path + "|" + std::to_string((int)target_rate);
    size_t budget, used;
    {
        std::lock_guard<std::mutex> l(m_);
        auto it = cache_.find(key);
        if (it != cache_.end()) { r.buffer = it->second.buf; r.error = it->second.error; r.source = it->second.src; return r; }
        budget = budget_; used = bytes_used_locked();
    }
    // Decode outside the lock (can take a while for long files).
    WavInfo hdr;
    WavError he = WavReader::read_info(path, hdr);
    if (he != WavError::Ok && he != WavError::Truncated) { r.error = map_error(he); return r; }
    r.source = hdr;
    size_t decoded = (size_t)std::max<int64_t>(hdr.frames, 0) * hdr.channels * sizeof(float);
    // Budget check includes the resampled copy that may briefly coexist.
    if (used + decoded * 2 > budget) {
        // Try freeing unused entries first.
        trim();
        { std::lock_guard<std::mutex> l(m_); used = bytes_used_locked(); }
        if (used + decoded * 2 > budget) { r.error = kMediaTooLarge; return r; }
    }
    try {
        WavError le = WavError::Ok;
        auto raw = WavReader::load(path, &le, budget);
        if (!raw) { r.error = map_error(le); return r; }
        int err = map_error(le);
        std::shared_ptr<AudioBuffer> out = raw;
        if (std::fabs(raw->sample_rate() - target_rate) > 0.5f) {
            out = resample_buffer(raw, target_rate);
            if (!out) { r.error = kMediaOutOfMemory; return r; }
            out->build_peak_table();
        }
        r.buffer = out; r.error = err;
    } catch (const std::bad_alloc&) {
        r.buffer.reset(); r.error = kMediaOutOfMemory; return r;
    }
    std::lock_guard<std::mutex> l(m_);
    cache_[key] = Entry{r.buffer, r.error, r.source};
    return r;
}

} // namespace wavestudio
