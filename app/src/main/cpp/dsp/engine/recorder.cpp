#include "recorder.h"
#include <chrono>
#include <algorithm>

namespace wavestudio {

namespace {
constexpr size_t kRingFloats = 1u << 21;   // 2M floats: ~21 s of stereo @ 48 kHz of disk-stall tolerance
}

Recorder::Recorder() : ring_(kRingFloats) {}
Recorder::~Recorder() { if (run_.load()) stop(); }

bool Recorder::start(const std::string& path, int channels, int sample_rate, int bit_depth) {
    if (run_.load() || channels < 1 || channels > 2) return false;
    if (!writer_.open(path, channels, sample_rate, bit_depth, false)) return false;
    // Make the (valid, empty) header durable immediately: a crash right after start leaves a valid file.
    writer_.checkpoint();
    path_ = path; channels_ = channels; sr_ = sample_rate;
    dropped_ = 0; pushed_ = 0; written_ = 0; io_error_ = false;
    float tmp[256];
    while (ring_.pop(tmp, 256) > 0) {}
    run_.store(true, std::memory_order_release);
    thread_ = std::thread([this] { writer_loop(); });
    active_.store(true, std::memory_order_release);
    return true;
}

void Recorder::push(const float* x, int frames) {
    if (!active_.load(std::memory_order_acquire)) return;
    const size_t n = (size_t)frames * channels_;
    const size_t w = ring_.push(x, n);
    pushed_.fetch_add(frames, std::memory_order_relaxed);
    if (w < n) dropped_.fetch_add((int64_t)((n - w) / channels_) + 1, std::memory_order_relaxed);
}

void Recorder::writer_loop() {
    std::vector<float> chunk(4096 * 2);
    auto last_cp = std::chrono::steady_clock::now();
    for (;;) {
        const bool stopping = !run_.load(std::memory_order_acquire);
        size_t got = ring_.pop(chunk.data(), (chunk.size() / channels_) * channels_);
        if (got > 0) {
            if (!writer_.write(chunk.data(), (int64_t)(got / channels_))) io_error_.store(true);
            written_.fetch_add((int64_t)(got / channels_));
            continue;
        }
        if (stopping) break;
        auto now = std::chrono::steady_clock::now();
        if (now - last_cp > std::chrono::seconds(1)) { writer_.checkpoint(); last_cp = now; }
        std::this_thread::sleep_for(std::chrono::milliseconds(4));
    }
}

Recorder::Result Recorder::stop() {
    Result r;
    if (!run_.load()) return r;
    active_.store(false, std::memory_order_release);
    // Let an audio callback that was mid-push finish before the writer drains for the last time.
    std::this_thread::sleep_for(std::chrono::milliseconds(60));
    run_.store(false, std::memory_order_release);
    if (thread_.joinable()) thread_.join();
    r.path = path_;
    r.channels = channels_;
    r.sample_rate = sr_;
    r.frames = written_.load();
    r.dropped_frames = dropped_.load();
    r.io_error = io_error_.load() || writer_.had_error();
    r.ok = writer_.close() && !r.io_error;
    r.frames = r.ok ? writer_.frames_written() : r.frames;
    return r;
}

} // namespace wavestudio
