#pragma once
// Thin AAudio device layer around EngineCore. It owns the streams and nothing else: all mixing,
// effects and recording logic lives in engine/engine_core.* so it can be host-tested.
#include <atomic>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>
#include <aaudio/AAudio.h>
#include "engine/engine_core.h"

namespace wavestudio {

struct DeviceStatus {
    bool output_running = false;
    bool input_running = false;
    int sample_rate = 0;
    int output_channels = 0;
    int input_channels = 0;
    int frames_per_burst = 0;
    int buffer_frames = 0;
    int xruns = 0;                  // AAudio underrun count of the output stream
    int input_underruns = 0;        // output callbacks that found less input than needed
    int restarts = 0;               // automatic stream re-opens (route change / disconnect)
    int last_error = 0;             // last AAudio result code that was not OK
    float est_output_latency_ms = 0;   // buffer-size based ESTIMATE, not a measurement
    float est_input_latency_ms = 0;
    int est_round_trip_frames = 0;
    bool low_latency_mmap = false;
};

class AudioDevice {
public:
    static AudioDevice& instance();

    // Opens the output stream (and the input stream if `want_input`). `preferred_rate` 0 = device
    // native rate. Returns false when no output stream could be opened. The core is created on
    // demand, and rebuilt from the model (clone) if the device picks a different rate.
    bool start(int preferred_rate, bool want_input);
    void stop();
    bool running() const { return out_ != nullptr; }
    bool enable_input(bool on);                  // e.g. after RECORD_AUDIO was granted
    DeviceStatus status();

    std::shared_ptr<EngineCore> core();          // never null
    void reset_core_for_new_project();           // drops all tracks / clips / effects

private:
    AudioDevice() = default;
    bool open_output(int rate);
    bool open_input(int rate);
    void close_streams();
    void schedule_reopen();

    static aaudio_data_callback_result_t out_cb(AAudioStream*, void* user, void* data, int32_t frames);
    static aaudio_data_callback_result_t in_cb(AAudioStream*, void* user, void* data, int32_t frames);
    static void err_cb(AAudioStream*, void* user, aaudio_result_t err);

    std::mutex mu_;                              // serialises start/stop/reopen (never taken in callbacks)
    std::shared_ptr<EngineCore> core_;
    AAudioStream* out_ = nullptr;
    AAudioStream* in_ = nullptr;
    int rate_ = 0, out_ch_ = 2, in_ch_ = 0;
    bool want_input_ = false;
    std::atomic<bool> reopening_{false};
    std::atomic<int> restarts_{0}, last_error_{0};
    std::atomic<int> input_underruns_{0};
    int preferred_rate_ = 0;
    std::unique_ptr<SpscRing<float>> in_ring_;
    std::vector<float> in_scratch_;
    EngineCore* cb_core_ = nullptr;              // valid between stream open and close
    std::atomic<int> in_channels_cb_{0};
};

} // namespace wavestudio
