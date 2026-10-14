#pragma once
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include "../buffer.h"
#include "../file_io/wav_reader.h"

namespace wavestudio {

// Error codes shared with Kotlin (negative values over JNI). 0 = ok.
enum MediaError : int {
    kMediaOk = 0,
    kMediaOpenFailed = 1,
    kMediaNotWav = 2,
    kMediaUnsupported = 3,
    kMediaTruncated = 4,      // loaded, but the file ended early (partial audio is returned)
    kMediaTooLarge = 5,       // would exceed the memory budget
    kMediaOutOfMemory = 6,
};

// Decoded-audio cache shared by the live engine and offline export. Buffers are decoded and
// resampled once per (path, engine rate) and are immutable afterwards. Control thread only.
class MediaLibrary {
public:
    struct Result {
        std::shared_ptr<AudioBuffer> buffer;   // at the requested rate (null on hard failure)
        int error = kMediaOk;                  // kMediaTruncated still carries a usable buffer
        WavInfo source;                        // header info of the file on disk
    };

    explicit MediaLibrary(size_t budget_bytes = 640u * 1024u * 1024u) : budget_(budget_bytes) {}

    Result get(const std::string& path, float target_rate);
    bool info(const std::string& path, WavInfo& out, int* error = nullptr);
    void invalidate(const std::string& path);      // file on disk changed
    void set_budget(size_t bytes) { std::lock_guard<std::mutex> l(m_); budget_ = bytes; }
    size_t bytes_used() const;
    void trim();                                   // drop cache entries nobody uses

private:
    static int map_error(WavError e);
    size_t bytes_used_locked() const;
    struct Entry { std::shared_ptr<AudioBuffer> buf; int error; WavInfo src; };
    mutable std::mutex m_;
    std::map<std::string, Entry> cache_;   // key: path + '|' + rate
    size_t budget_;
};

} // namespace wavestudio
