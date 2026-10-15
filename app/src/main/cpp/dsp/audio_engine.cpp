#include "audio_engine.h"
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <dlfcn.h>

#define LOG_TAG "WaveStudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace wavestudio {

namespace {
/** Resolve an AAudio entry point that is newer than minSdk at run time (nullptr when the device does not have it). */
template <class Fn> Fn aaudio_sym(const char* name) {
    static void* lib = dlopen("libaaudio.so", RTLD_NOW);
    return lib ? reinterpret_cast<Fn>(dlsym(lib, name)) : nullptr;
}
} // namespace

AudioDevice& AudioDevice::instance() {
    static AudioDevice* inst = new AudioDevice();   // intentionally leaked: callbacks may outlive static destruction
    return *inst;
}

std::shared_ptr<EngineCore> AudioDevice::core() {
    std::lock_guard<std::mutex> l(mu_);
    if (!core_) core_ = std::make_shared<EngineCore>(std::make_shared<MediaLibrary>(), 48000.f);
    return core_;
}

void AudioDevice::reset_core_for_new_project() {
    std::lock_guard<std::mutex> l(mu_);
    const bool was_running = out_ != nullptr;
    if (was_running) close_streams();
    const float rate = core_ ? core_->engine_rate() : 48000.f;
    core_ = std::make_shared<EngineCore>(std::make_shared<MediaLibrary>(), rate);
    if (was_running) {
        if (open_output(preferred_rate_ ? preferred_rate_ : (int)rate)) {
            cb_core_ = core_.get();
            if (want_input_) open_input(rate_);
            AAudioStream_requestStart(out_);
            if (in_) AAudioStream_requestStart(in_);
        }
    }
}

// ---------------------------------------------------------------------------------------------
// callbacks (real-time: no locks, no allocation, no logging)
// ---------------------------------------------------------------------------------------------
aaudio_data_callback_result_t AudioDevice::out_cb(AAudioStream*, void* user, void* data, int32_t frames) {
    auto* self = static_cast<AudioDevice*>(user);
    EngineCore* core = self->cb_core_;
    float* out = static_cast<float*>(data);
    if (!core) { std::fill(out, out + (size_t)frames * 2, 0.f); return AAUDIO_CALLBACK_RESULT_CONTINUE; }
    const int in_ch = self->in_channels_cb_.load(std::memory_order_acquire);
    int32_t done = 0;
    const int32_t kMax = (int32_t)(self->in_scratch_.size() / 2);
    while (done < frames) {
        const int32_t n = std::min(kMax, frames - done);
        const float* in = nullptr;
        if (in_ch > 0 && self->in_ring_) {
            float* sc = self->in_scratch_.data();
            const size_t want = (size_t)n * in_ch;
            // Keep the ring from drifting into extra latency: discard the oldest input beyond ~4 bursts.
            const size_t avail = self->in_ring_->size();
            const size_t keep = want * 4;
            if (avail > want + keep) self->in_ring_->skip(avail - want - keep);
            const size_t got = self->in_ring_->pop(sc, want);
            if (got < want) {
                std::fill(sc + got, sc + want, 0.f);        // input glitch: stay in sync, count it
                self->input_underruns_.fetch_add(1, std::memory_order_relaxed);
                core->note_underrun();
            }
            in = sc;
        }
        core->render(out + (size_t)done * 2, n, in, in_ch);
        done += n;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

aaudio_data_callback_result_t AudioDevice::in_cb(AAudioStream*, void* user, void* data, int32_t frames) {
    auto* self = static_cast<AudioDevice*>(user);
    const int ch = self->in_channels_cb_.load(std::memory_order_acquire);
    if (ch > 0 && self->in_ring_) self->in_ring_->push(static_cast<const float*>(data), (size_t)frames * ch);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AudioDevice::err_cb(AAudioStream*, void* user, aaudio_result_t err) {
    auto* self = static_cast<AudioDevice*>(user);
    self->last_error_.store(err);
    // Streams must not be closed from inside their own error callback: hand off to a worker.
    if (err == AAUDIO_ERROR_DISCONNECTED || err == AAUDIO_ERROR_TIMEOUT || err == AAUDIO_ERROR_INVALID_STATE ||
        err == AAUDIO_ERROR_NULL) {
        self->schedule_reopen();
    }
}

void AudioDevice::schedule_reopen() {
    bool expected = false;
    if (!reopening_.compare_exchange_strong(expected, true)) return;
    std::thread([this] {
        std::this_thread::sleep_for(std::chrono::milliseconds(150));   // let the route settle
        {
            std::lock_guard<std::mutex> l(mu_);
            if (out_ != nullptr) {                                       // still supposed to be running
                const bool wi = want_input_;
                close_streams();
                for (int attempt = 0; attempt < 5; ++attempt) {
                    if (open_output(preferred_rate_)) {
                        if (core_->engine_rate() != (float)rate_) core_ = core_->clone((float)rate_);
                        cb_core_ = core_.get();
                        if (wi) open_input(rate_);
                        AAudioStream_requestStart(out_);
                        if (in_) AAudioStream_requestStart(in_);
                        restarts_.fetch_add(1);
                        LOGI("streams re-opened (rate %d)", rate_);
                        break;
                    }
                    std::this_thread::sleep_for(std::chrono::milliseconds(300));
                }
            }
        }
        reopening_.store(false);
    }).detach();
}

// ---------------------------------------------------------------------------------------------
// stream management (control thread, mu_ held)
// ---------------------------------------------------------------------------------------------
bool AudioDevice::open_output(int rate) {
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    // Usage / content type are API 28+; minSdk is 26, so guard them at run time.
    // The NDK headers mark these "introduced in 28" and reject direct calls at minSdk 26, so resolve them with dlsym.
    if (auto f = aaudio_sym<void (*)(AAudioStreamBuilder*, int32_t)>("AAudioStreamBuilder_setUsage")) f(b, AAUDIO_USAGE_MEDIA);
    if (auto f = aaudio_sym<void (*)(AAudioStreamBuilder*, int32_t)>("AAudioStreamBuilder_setContentType")) f(b, AAUDIO_CONTENT_TYPE_MUSIC);
    if (rate > 0) AAudioStreamBuilder_setSampleRate(b, rate);
    AAudioStreamBuilder_setDataCallback(b, &AudioDevice::out_cb, this);
    AAudioStreamBuilder_setErrorCallback(b, &AudioDevice::err_cb, this);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &out_);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { last_error_.store(r); out_ = nullptr; LOGE("open output failed: %s", AAudio_convertResultToText(r)); return false; }
    rate_ = AAudioStream_getSampleRate(out_);
    out_ch_ = AAudioStream_getChannelCount(out_);
    const int burst = AAudioStream_getFramesPerBurst(out_);
    AAudioStream_setBufferSizeInFrames(out_, burst * 2);          // double-buffered: low latency, tolerant of one late burst
    if (in_scratch_.empty()) in_scratch_.assign(8192 * 2, 0.f);
    if (out_ch_ != 2) {
        LOGE("output has %d channels (stereo required)", out_ch_);
        AAudioStream_close(out_); out_ = nullptr; return false;
    }
    return true;
}

bool AudioDevice::open_input(int rate) {
    if (input_dev_pref_ > 0 && open_input_id(rate, input_dev_pref_)) return true;
    if (input_dev_pref_ > 0) LOGE("input device %d could not be opened, using the default", input_dev_pref_);
    return open_input_id(rate, 0);
}

bool AudioDevice::open_input_id(int rate, int dev_id) {
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    using SetPresetFn = void (*)(AAudioStreamBuilder*, int32_t);
    SetPresetFn set_preset = aaudio_sym<SetPresetFn>("AAudioStreamBuilder_setInputPreset");   // API 28+
    bool preset_set = false;
    if (set_preset) {
        set_preset(b, AAUDIO_INPUT_PRESET_UNPROCESSED);   // no AGC / noise suppression on a music recording
        preset_set = true;
    }
    if (rate > 0) AAudioStreamBuilder_setSampleRate(b, rate);
    if (dev_id > 0) AAudioStreamBuilder_setDeviceId(b, dev_id);
    AAudioStreamBuilder_setDataCallback(b, &AudioDevice::in_cb, this);
    AAudioStreamBuilder_setErrorCallback(b, &AudioDevice::err_cb, this);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &in_);
    if (r != AAUDIO_OK && preset_set) {
        // Some devices reject UNPROCESSED: retry with the generic preset.
        if (set_preset) set_preset(b, AAUDIO_INPUT_PRESET_GENERIC);
        r = AAudioStreamBuilder_openStream(b, &in_);
    }
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { last_error_.store(r); in_ = nullptr; LOGE("open input failed: %s", AAudio_convertResultToText(r)); return false; }
    const int ch = AAudioStream_getChannelCount(in_);
    if (AAudioStream_getSampleRate(in_) != rate_ || ch < 1 || ch > 2) {
        LOGE("input stream rate/channels unsupported (%d Hz, %d ch)", AAudioStream_getSampleRate(in_), ch);
        AAudioStream_close(in_); in_ = nullptr; return false;
    }
    if (!in_ring_) in_ring_ = std::make_unique<SpscRing<float>>(1u << 16);
    in_ring_->skip(in_ring_->size());          // stale audio from a previous stream; callbacks are idle (ch == 0)
    in_ch_ = ch;
    in_channels_cb_.store(ch, std::memory_order_release);
    AAudioStream_setBufferSizeInFrames(in_, AAudioStream_getFramesPerBurst(in_) * 2);
    return true;
}

void AudioDevice::close_streams() {
    cb_core_ = nullptr;
    in_channels_cb_.store(0, std::memory_order_release);
    // AAudioStream_close blocks until the callback has returned, so after this nothing touches the core.
    if (out_) { AAudioStream_requestStop(out_); AAudioStream_close(out_); out_ = nullptr; }
    if (in_) { AAudioStream_requestStop(in_); AAudioStream_close(in_); in_ = nullptr; }
    in_ch_ = 0;
}

bool AudioDevice::start(int preferred_rate, bool want_input) {
    std::lock_guard<std::mutex> l(mu_);
    if (out_) { return true; }
    preferred_rate_ = preferred_rate;
    want_input_ = want_input;
    if (!core_) core_ = std::make_shared<EngineCore>(std::make_shared<MediaLibrary>(), 48000.f);
    if (!open_output(preferred_rate)) return false;
    if (core_->engine_rate() != (float)rate_) {
        LOGI("device rate %d differs from core rate %.0f: rebuilding core", rate_, core_->engine_rate());
        core_ = core_->clone((float)rate_);
    }
    cb_core_ = core_.get();
    if (want_input) open_input(rate_);
    // Round-trip latency estimate used to shift recorded takes onto the timeline.
    const int64_t comp = AAudioStream_getBufferSizeInFrames(out_) + (in_ ? AAudioStream_getBufferSizeInFrames(in_) : 0);
    core_->set_input_latency_frames(comp);
    if (AAudioStream_requestStart(out_) != AAUDIO_OK) { close_streams(); return false; }
    if (in_) AAudioStream_requestStart(in_);
    return true;
}

bool AudioDevice::set_input_device(int id) {
    std::lock_guard<std::mutex> l(mu_);
    id = id < 0 ? 0 : id;
    if (id == input_dev_pref_ && (in_ != nullptr || !want_input_)) return in_ != nullptr;
    input_dev_pref_ = id;
    if (!out_ || !want_input_) return in_ != nullptr;
    if (in_) {                                   // reopen on the new device
        in_channels_cb_.store(0, std::memory_order_release);
        AAudioStream_requestStop(in_); AAudioStream_close(in_); in_ = nullptr; in_ch_ = 0;
    }
    if (!open_input(rate_)) return false;
    core_->set_input_latency_frames(AAudioStream_getBufferSizeInFrames(out_) + AAudioStream_getBufferSizeInFrames(in_));
    AAudioStream_requestStart(in_);
    return true;
}

bool AudioDevice::enable_input(bool on) {
    std::lock_guard<std::mutex> l(mu_);
    want_input_ = on;
    if (!out_) return false;
    if (on && !in_) {
        if (!open_input(rate_)) return false;
        const int64_t comp = AAudioStream_getBufferSizeInFrames(out_) + AAudioStream_getBufferSizeInFrames(in_);
        core_->set_input_latency_frames(comp);
        AAudioStream_requestStart(in_);
        return true;
    }
    if (!on && in_) {
        in_channels_cb_.store(0, std::memory_order_release);
        AAudioStream_requestStop(in_); AAudioStream_close(in_); in_ = nullptr; in_ch_ = 0;
    }
    return in_ != nullptr || !on;
}

void AudioDevice::stop() {
    std::lock_guard<std::mutex> l(mu_);
    close_streams();
}

DeviceStatus AudioDevice::status() {
    std::lock_guard<std::mutex> l(mu_);
    DeviceStatus s;
    s.output_running = out_ != nullptr;
    s.input_running = in_ != nullptr;
    s.input_channels = in_ch_;
    s.input_device_id = in_ ? AAudioStream_getDeviceId(in_) : 0;
    s.restarts = restarts_.load();
    s.last_error = last_error_.load();
    s.input_underruns = input_underruns_.load();
    if (out_) {
        s.sample_rate = rate_;
        s.output_channels = out_ch_;
        s.frames_per_burst = AAudioStream_getFramesPerBurst(out_);
        s.buffer_frames = AAudioStream_getBufferSizeInFrames(out_);
        s.xruns = AAudioStream_getXRunCount(out_);
        s.est_output_latency_ms = 1000.f * s.buffer_frames / std::max(1, rate_);
        s.low_latency_mmap = AAudioStream_getPerformanceMode(out_) == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
    }
    s.est_round_trip_frames = (out_ ? AAudioStream_getBufferSizeInFrames(out_) : 0) + (in_ ? AAudioStream_getBufferSizeInFrames(in_) : 0);
    if (in_) s.est_input_latency_ms = 1000.f * AAudioStream_getBufferSizeInFrames(in_) / std::max(1, rate_);
    return s;
}

} // namespace wavestudio
