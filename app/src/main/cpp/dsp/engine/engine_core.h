#pragma once
// Device-independent audio engine: transport, tracks, clips, effect racks, metronome,
// recording hand-off, meters and analysis. The device layer (AAudio) only calls render().
//
// Threading contract
//   * render() is the ONLY audio-thread entry point. It takes no locks, allocates nothing and does
//     no I/O. It consumes immutable snapshots handed over through a single atomic pointer and
//     lock-free rings (parameter changes, retired snapshots, analysis audio, recorder input).
//   * Everything else is the "control side": serialised by an internal mutex, free to allocate
//     and block. The control side owns the editable model; each structural edit builds a fresh
//     Snapshot (shared_ptr references to audio buffers and effect instances) and publishes it.
//   * Old snapshots are returned to the control side through a retire ring and freed there, so
//     the audio thread never calls free().
#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include "rt_utils.h"
#include "media.h"
#include "recorder.h"
#include "../effects/effect.h"
#include "../analysis/lufs.h"
#include "../analysis/spectrum.h"

namespace wavestudio {

struct ClipParams {
    int id = 0;
    int track_id = 0;
    std::string path;
    int64_t start = 0;        // timeline frames (project rate)
    int64_t offset = 0;       // source offset, timeline frames (same time base)
    int64_t length = 0;       // timeline frames; clamped to the source length
    float gain_db = 0.f;
    int64_t fade_in = 0;      // timeline frames
    int64_t fade_out = 0;
};

struct EffectParamInfo {
    std::string id, display, unit;
    float min = 0, max = 1, def = 0, value = 0;
};
struct EffectInfo {
    int id = 0;
    int track_id = 0;          // 0 = master
    std::string type, name;
    bool bypass = false;
    std::vector<EffectParamInfo> params;
};

struct MasterMeters {
    float peak_l = 0, peak_r = 0, rms_l = 0, rms_r = 0;
    int64_t clip_count = 0;
};

class EngineCore {
public:
    static constexpr int kMaxTracks = 64;
    static constexpr int kMaxChunk = 512;
    static constexpr int kMaxEffectsPerChain = 16;
    static constexpr int kMaxBuses = 4;       // aux send buses; bus b is addressed as effect-chain id -(b+1)

    EngineCore(std::shared_ptr<MediaLibrary> media, float engine_rate, float timeline_rate = 48000.f);
    ~EngineCore();
    EngineCore(const EngineCore&) = delete;
    EngineCore& operator=(const EngineCore&) = delete;

    // ---------------- audio thread ----------------
    // `out`: interleaved stereo, `frames` frames. `in`: interleaved input (may be null),
    // `in_channels` 1 or 2.
    void render(float* out, int frames, const float* in, int in_channels);

    // ---------------- transport ----------------
    void play();
    void pause();
    void seek(int64_t timeline_frame);
    int64_t position() const;                 // timeline frames
    bool is_playing() const { return play_req_.load() != 0; }
    bool is_counting_in() const { return counting_pub_.load(); }
    void set_loop(int64_t start, int64_t end, bool enabled);
    void set_metronome(bool on, float bpm, int beats_per_bar);
    void set_count_in_bars(int bars);
    void set_master_volume(float v);          // linear, 0..2

    // ---------------- model (control thread) ----------------
    bool add_track(int id);
    bool remove_track(int id);
    bool has_track(int id) const;
    void set_track_volume(int id, float v);   // linear, 0..4
    void set_track_pan(int id, float p);      // -1..1
    void set_track_mute(int id, bool m);
    void set_track_solo(int id, bool s);
    // Aux sends: post-fader/post-pan feed of a track into bus `bus` (0..kMaxBuses-1). Level is linear 0..2.
    // Each bus has its own effect chain (add_effect with track_id = -(bus+1)) and a return level (0..2).
    void set_track_send(int id, int bus, float level);
    void set_bus_level(int bus, float level);

    // Returns MediaError (0 ok / 4 truncated-but-usable; others = clip not added).
    int set_clip(const ClipParams& c);
    bool remove_clip(int clip_id);
    void clear_clips();
    int64_t project_end() const;              // last clip end, timeline frames
    // Waveform overview of a media file: `bins` min/max pairs for source time [t0,t1) seconds.
    // Returns false if the file cannot be decoded (out is zero-filled).
    bool get_peaks(const std::string& path, double t0_sec, double t1_sec, int bins, float* out);
    std::shared_ptr<MediaLibrary> media() const { return media_; }

    // Effects. track_id 0 = master, -1..-kMaxBuses = aux bus chains. Returns the new effect id or a negative error.
    int add_effect(int track_id, const std::string& type, int forced_id = 0, int index = -1);
    bool remove_effect(int effect_id);
    bool move_effect(int effect_id, int new_index);
    bool set_effect_param(int effect_id, int param_index, float value);
    bool set_effect_bypass(int effect_id, bool bypass);
    bool get_effect_info(int effect_id, EffectInfo& out) const;
    std::vector<int> list_effects(int track_id) const;

    // ---------------- recording ----------------
    bool start_recording(const std::string& path, int input_channels, int bit_depth = 24);
    Recorder::Result stop_recording(int64_t* timeline_start = nullptr);
    bool is_recording() const { return recorder_.active(); }
    void set_punch(bool enabled, int64_t in_frame, int64_t out_frame);
    void set_input_latency_frames(int64_t f) { latency_comp_.store(f); }
    void set_monitor(bool on, float gain);
    float input_peak();                       // read-and-reset
    int64_t recording_dropped_frames() const { return recorder_.dropped_frames(); }

    // ---------------- meters / analysis ----------------
    MasterMeters master_meters();
    bool track_meters(int id, float* peak_l, float* peak_r);   // read-and-reset
    float lufs_integrated();
    float lufs_momentary();
    float lufs_short_term();
    float correlation();
    void reset_loudness();
    void get_spectrum(float* bins, int n);
    int64_t underrun_blocks() const { return underruns_.load(); }
    void note_underrun() { underruns_.fetch_add(1); }
    int64_t render_count() const { return renders_.load(); }
    float max_render_load() ;                 // worst render time / buffer duration since last call

    // ---------------- misc ----------------
    float engine_rate() const { return engine_rate_; }
    float timeline_rate() const { return timeline_rate_; }
    // Replays the model into a new core (fresh effect instances, same ids) for offline export or
    // for a device rate change. Not valid while the source is being rendered by the audio thread
    // for *state*; the model itself is copied under the control lock.
    std::unique_ptr<EngineCore> clone(float new_engine_rate) const;
    // Offline render with the SAME code path as live playback. Returns interleaved stereo.
    // Stops after `frames` engine frames. Transport is moved to `start_tl`.
    bool render_offline(int64_t start_tl, int64_t frames, float* out_interleaved_stereo,
                        int64_t chunk = 4096);
    void prepare_offline();                   // metronome/loop off, transport at play state

private:
    // ---- immutable audio-side snapshot ----
    struct SClip {
        const AudioBuffer* buf = nullptr;
        std::shared_ptr<AudioBuffer> keep;
        int64_t start = 0, len = 0, off = 0;
        float gain = 1.f;
        int64_t fin = 0, fout = 0;
    };
    struct SSlot { Effect* fx = nullptr; std::shared_ptr<Effect> keep; int id = 0; bool bypass = false; };
    struct STrack { int id = 0; int slot = 0; std::vector<SClip> clips; std::vector<SSlot> fx; };
    struct Snapshot {
        std::vector<STrack> tracks;
        std::vector<SSlot> master;
        std::array<std::vector<SSlot>, kMaxBuses> bus;
        bool loop = false;
        int64_t loop_s = 0, loop_e = 0;
        bool punch = false;
        int64_t punch_in = 0, punch_out = 0;
    };
    struct ParamMsg { int effect_id; int index; float value; };

    // ---- control-side model ----
    struct MEffect {
        int id = 0;
        std::string type;
        std::shared_ptr<Effect> fx;
        std::vector<float> values;
        bool bypass = false;
    };
    struct MTrack {
        int id = 0; int slot = 0;
        std::vector<MEffect> fx;
        float vol = 1.f, pan = 0.f; bool mute = false, solo = false;
        std::array<float, kMaxBuses> send{};
    };
    struct MClip { ClipParams p; std::shared_ptr<AudioBuffer> buf; int64_t len_tl_clamped = 0; };

    struct TrackState {
        std::atomic<float> vol{1.f}, pan{0.f};
        std::atomic<bool> mute{false}, solo{false};
        std::atomic<float> peak_l{0.f}, peak_r{0.f};
        std::atomic<float> send[kMaxBuses] = {};
        std::atomic<bool> fresh{true};     // first render after creation: jump to target gain, no ramp
    };

    // control helpers (mu_ held)
    void publish_locked();
    void collect_garbage_locked();
    void pump_analysis_locked();
    MTrack* find_track(int id);
    const MTrack* find_track(int id) const;
    // Locates an effect in the master chain, a bus chain or a track chain. `chain` and `owner_id` (0 master, <0 bus, >0 track) are optional outputs.
    MEffect* find_effect(int id, MTrack** owner, int* chain_index, std::vector<MEffect>** chain = nullptr, int* owner_id = nullptr);
    std::vector<MEffect>& chain_of(int track_id, bool* ok);
    int64_t tl_to_engine(int64_t tl) const;
    int64_t engine_to_tl(int64_t e) const;
    void apply_track_state(const MTrack& t);

    // audio helpers
    int render_chunk_planned(float* out, int n, const float* in, int in_ch);
    void mix_clip(const SClip& c, int64_t pos, int n, float* L, float* R);
    void run_chain(const std::vector<SSlot>& chain, float* L, float* R, int n);
    void metronome_chunk(float* L, float* R, int n, int64_t beat_pos);
    void apply_params();

    // ---- state ----
    std::shared_ptr<MediaLibrary> media_;
    float engine_rate_, timeline_rate_;
    mutable std::mutex mu_;

    // control model
    std::vector<MTrack> tracks_;
    std::vector<MEffect> master_fx_;
    std::array<std::vector<MEffect>, kMaxBuses> bus_fx_;
    std::vector<MClip> clips_;
    std::array<bool, kMaxTracks> slot_used_{};
    int next_effect_id_ = 1;
    bool loop_en_ = false; int64_t loop_s_tl_ = 0, loop_e_tl_ = 0;
    bool punch_en_ = false; int64_t punch_in_tl_ = 0, punch_out_tl_ = 0;
    LufsMeter lufs_; SpectrumAnalyzer spectrum_; PhaseMeter phase_;
    std::vector<float> analysis_tmp_;
    int64_t rec_timeline_start_ = 0;

    // hand-off
    std::atomic<Snapshot*> pending_{nullptr};
    SpscRing<Snapshot*> retire_;
    SpscRing<ParamMsg> params_;
    SpscRing<float> analysis_;
    std::array<TrackState, kMaxTracks> tstate_;
    Snapshot* current_ = nullptr;          // audio thread only

    // transport (audio-owned unless atomic)
    std::atomic<int> play_req_{0};
    std::atomic<int64_t> seek_req_{-1};
    std::atomic<int64_t> pos_pub_{0};
    std::atomic<bool> counting_pub_{false};
    std::atomic<int> count_in_bars_{0};
    std::atomic<bool> metro_on_{false};
    std::atomic<float> bpm_{120.f};
    std::atomic<int> beats_{4};
    std::atomic<float> master_vol_{1.f};
    std::atomic<int64_t> latency_comp_{0};
    std::atomic<bool> monitor_on_{false};
    std::atomic<float> monitor_gain_{1.f};
    std::atomic<int64_t> rec_first_pos_{-1};
    std::atomic<int> reset_req_{0};
    std::atomic<int> rec_gen_{0};
    int rec_gen_seen_ = 0;
    bool snap_gains_ = false;
    int64_t pos_ = 0;                      // engine frames
    bool playing_a_ = false, counting_a_ = false;
    int64_t count_left_ = 0, count_pos_ = 0;
    int64_t tail_left_ = 0;
    bool rec_stalled_ = false;
    int64_t rec_expected_pos_ = -1;

    // metronome click synth state (audio)
    int click_left_ = 0, click_total_ = 0; float click_phase_ = 0.f, click_inc_ = 0.f, click_amp_ = 0.f;

    // meters
    std::atomic<float> m_peak_l_{0.f}, m_peak_r_{0.f}, m_rms_l_{0.f}, m_rms_r_{0.f};
    std::atomic<int64_t> clip_count_{0};
    std::atomic<float> in_peak_{0.f};
    std::atomic<int64_t> underruns_{0}, renders_{0};
    std::atomic<float> max_load_{0.f};
    double rms_acc_l_ = 0, rms_acc_r_ = 0;

    // audio scratch (fixed-size, preallocated)
    std::array<float, kMaxChunk> bufL_{}, bufR_{}, trkL_{}, trkR_{}, inL_{}, inR_{};
    std::array<float, kMaxChunk * 2> recbuf_{};
    std::array<float, kMaxTracks> gain_l_{}, gain_r_{};
    std::array<std::array<float, kMaxChunk>, kMaxBuses> busL_{}, busR_{};
    std::array<float, kMaxChunk> pfL_{}, pfR_{};
    std::array<std::array<float, kMaxBuses>, kMaxTracks> send_cur_{};
    std::array<float, kMaxBuses> bus_gain_cur_{};
    std::array<int64_t, kMaxBuses> bus_idle_{};
    std::array<std::atomic<float>, kMaxBuses> bus_level_{};
    float master_gain_cur_ = 1.f;
    Recorder recorder_;
};

} // namespace wavestudio
