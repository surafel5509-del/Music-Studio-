#pragma once
#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <thread>
#include <vector>
#include "rt_utils.h"
#include "../file_io/wav_stream_writer.h"

namespace wavestudio {

// Disk recorder. The audio thread only calls push() (lock-free ring write); a writer thread
// streams the ring to a WAV file and checkpoints the header about once per second, so a crash
// loses at most ~1 s of audio and the file stays a valid WAV. Frames that do not fit into the
// ring (disk too slow) are counted, never silently ignored.
class Recorder {
public:
    struct Result {
        std::string path;
        int64_t frames = 0;
        int64_t dropped_frames = 0;
        int channels = 0;
        int sample_rate = 0;
        bool io_error = false;     // disk full or write failure
        bool ok = false;
    };

    Recorder();
    ~Recorder();

    // Control thread. Fails (false) if the file cannot be created.
    bool start(const std::string& path, int channels, int sample_rate, int bit_depth = 24);
    // Control thread. Flushes everything pushed so far and closes the file.
    Result stop();
    bool active() const { return active_.load(std::memory_order_acquire); }

    // Audio thread, real-time safe. `interleaved` has `frames` frames of channels().
    void push(const float* interleaved, int frames);

    int channels() const { return channels_; }
    int64_t dropped_frames() const { return dropped_.load(std::memory_order_relaxed); }
    int64_t frames_pushed() const { return pushed_.load(std::memory_order_relaxed); }

private:
    void writer_loop();
    SpscRing<float> ring_;
    std::atomic<bool> active_{false};         // audio thread may push
    std::atomic<bool> run_{false};            // writer thread alive
    std::atomic<int64_t> dropped_{0}, pushed_{0};
    std::atomic<bool> io_error_{false};
    std::atomic<int64_t> written_{0};
    std::thread thread_;
    WavStreamWriter writer_;
    std::string path_;
    int channels_ = 0, sr_ = 0;
};

} // namespace wavestudio
