#pragma once
#include <cstdint>
#include <atomic>
#include <thread>
#include <mutex>
#include <vector>
#include <memory>
#include <functional>
#include "buffer.h"
#include "mixer.h"
#include "effects/effect_chain.h"
#include "analysis/spectrum.h"
#include "analysis/lufs.h"
#include <aaudio/AAudio.h>
#include <SLES/OpenSLES.h>

// AAudioStream and aaudio_data_callback_result_t are defined in <aaudio/AAudio.h>
typedef struct AAudioStreamStruct AAudioStream;

namespace wavestudio {

enum class EngineState { Stopped, Playing, Recording, Paused };

struct TrackConfig {
    int id = 0;
    std::string name;
    float volume = 1.0f;   // linear
    float pan = 0.0f;      // -1..1
    bool mute = false;
    bool solo = false;
    bool arm = false;
    int input_channel = 0;
};

// Clip is an immutable reference to an AudioBuffer + non-destructive edits
struct Clip {
    int id = 0;
    int track_id = 0;
    std::shared_ptr<AudioBuffer> buffer;
    int64_t start_frame = 0;     // position on timeline
    int64_t offset_frame = 0;    // offset inside buffer
    int64_t length_frames = 0;   // visible length
    float gain_db = 0.f;
    float fade_in_ms = 0.f;
    float fade_out_ms = 0.f;
    bool reversed = false;
    float pitch_semitones = 0.f;
    float stretch_ratio = 1.f;   // 1.0 = no change
};

class AudioEngine {
public:
    static AudioEngine& instance();

    bool start(float sampleRate = 48000.f, int bufferSize = 0);
    void stop();

    // Transport
    void play();
    void pause();
    void stop_transport();
    void set_position(int64_t frame);
    int64_t position() const { return position_.load(); }
    void start_recording();
    void stop_recording();
    void set_loop(int64_t start_frame, int64_t end_frame, bool enabled);

    EngineState state() const { return state_.load(); }
    float sample_rate() const { return sample_rate_; }
    int buffer_size() const { return buffer_size_; }
    int input_channels() const { return input_channels_; }

    // Tracks
    int add_track(const std::string& name);
    void remove_track(int id);
    void set_track_config(int id, const TrackConfig& cfg);
    TrackConfig get_track_config(int id) const;
    TrackConfig* track_ptr(int id);
    std::vector<TrackConfig> tracks() const;

    // Clips
    int add_clip(const Clip& c);
    void remove_clip(int id);
    std::vector<Clip> clips_for_track(int trackId) const;
    std::vector<Clip> all_clips() const;

    // Effects
    EffectChain* track_chain(int trackId);
    EffectChain* master_chain();

    // Recording
    void set_input_device(int deviceId);
    void set_input_gain(float linear_gain);
    void arm_track(int id, bool armed, int channel = 0);

    // Metering / analysis
    float master_peak() const { return master_peak_.load(); }
    float master_rms() const { return master_rms_.load(); }
    float input_level() const { return input_level_.load(); }
    void get_spectrum(float* out_bins, int bins);
    float get_lufs_integrated() const;
    float get_lufs_momentary() const;

    // Master
    void set_master_volume(float v);
    float master_volume() const { return master_volume_.load(); }

    // Metronome
    void set_metronome(bool on, float bpm, int sig_num = 4, int sig_den = 4);

    // Callback for UI waveform updates
    void set_state_listener(std::function<void(EngineState, int64_t)> f) { listener_ = std::move(f); }

private:
    AudioEngine() = default;
    ~AudioEngine() { stop(); }
    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    bool open_streams(float requestedSampleRate, int requestedBuffer);
    void close_streams();
    void process_output(float** output, int numFrames, int numChannels);
    void process_input(const float** input, int numFrames, int numChannels);

    static aaudio_data_callback_result_t aaudio_out_cb(AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);
    static aaudio_data_callback_result_t aaudio_in_cb(AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);

    float sample_rate_ = 48000.f;
    int buffer_size_ = 512;
    int output_channels_ = 2;
    int input_channels_ = 0;
    int input_device_id_ = -1;

    AAudioStream* output_stream_ = nullptr;
    AAudioStream* input_stream_ = nullptr;

    std::atomic<EngineState> state_{EngineState::Stopped};
    std::atomic<int64_t> position_{0};
    std::atomic<float> master_volume_{1.f};
    std::atomic<float> master_peak_{0.f};
    std::atomic<float> master_rms_{0.f};
    std::atomic<float> input_level_{0.f};

    mutable std::mutex engine_mutex_;
    std::vector<TrackConfig> tracks_;
    std::vector<Clip> clips_;
    std::vector<std::unique_ptr<EffectChain>> track_effects_;
    EffectChain master_chain_;
    int next_track_id_ = 1;
    int next_clip_id_ = 1;

    Mixer mixer_;
    SpectrumAnalyzer spectrum_;
    LufsMeter lufs_;

    // Recording buffers: for each armed track
    std::vector<float> record_buffer_;
    std::mutex record_mutex_;
    float input_gain_ = 1.f;

    // Loop
    int64_t loop_start_ = 0, loop_end_ = 0;
    bool loop_enabled_ = false;

    // Metronome
    bool metronome_on_ = false;
    float metronome_bpm_ = 120.f;
    int metronome_sig_num_ = 4;
    int metronome_sig_den_ = 4;
    int64_t metro_count_ = 0;
    float metro_phase_ = 0.f;

    std::function<void(EngineState, int64_t)> listener_;
};

} // namespace wavestudio
