#include "audio_engine.h"
#include "mixer.h"
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <cmath>
#include <algorithm>
#include <cstring>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "WSEngine", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "WSEngine", __VA_ARGS__)

namespace wavestudio {

AudioEngine& AudioEngine::instance() {
    static AudioEngine inst;
    return inst;
}

bool AudioEngine::start(float sampleRate, int bufferSize) {
    std::lock_guard<std::mutex> lk(engine_mutex_);
    if (output_stream_) return true;
    if (!open_streams(sampleRate, bufferSize)) { close_streams(); return false; }

    // Start output first; input optional
    if (output_stream_) {
        aaudio_result_t r = AAudioStream_requestStart(output_stream_);
        if (r != AAUDIO_OK) { LOGE("Start output failed: %s", AAudio_convertResultToText(r)); close_streams(); return false; }
    }
    state_ = EngineState::Paused;
    spectrum_.init(2048, sample_rate_);
    lufs_.set_sample_rate(sample_rate_);
    lufs_.reset();
    LOGI("Engine started, SR=%.1f, buffer=%d", sample_rate_, buffer_size_);
    return true;
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lk(engine_mutex_);
    close_streams();
    state_ = EngineState::Stopped;
}

aaudio_data_callback_result_t AudioEngine::aaudio_out_cb(AAudioStream*, void* userData, void* audioData, int32_t numFrames) {
    auto* self = static_cast<AudioEngine*>(userData);
    int32_t ch = AAudioStream_getChannelCount(self->output_stream_);
    float* out = static_cast<float*>(audioData);
    std::memset(out, 0, sizeof(float)*numFrames*ch);

    EngineState s = self->state_.load();
    if (s == EngineState::Playing || s == EngineState::Recording) {
        // Build mixer track list
        std::vector<MixerTrack> mts;
        {
            std::lock_guard<std::mutex> g(self->engine_mutex_);
            for (auto& t : self->tracks_) {
                MixerTrack mt{};
                float pan = t.pan;
                mt.gain_l = t.volume * std::clamp(1.f - pan, 0.f, 1.f);
                mt.gain_r = t.volume * std::clamp(1.f + pan, 0.f, 1.f);
                mt.mute = t.mute;
                mt.solo = t.solo;
                mt.chain = (t.id-1 < (int)self->track_effects_.size()) ? self->track_effects_[t.id-1].get() : nullptr;
                // find first clip overlapping playhead for now
                int64_t ph = self->position_.load();
                for (auto& c : self->clips_) {
                    if (c.track_id == t.id && ph >= c.start_frame && ph < c.start_frame + c.length_frames) {
                        mt.clip = &c; break;
                    }
                }
                mts.push_back(mt);
            }
        }
        float masterL = self->master_volume_.load();
        float masterR = masterL;
        self->mixer_.mix(out, numFrames, ch, self->sample_rate_, mts, self->position_.load(), masterL, masterR, &self->master_chain_);

        // Metronome
        if (self->metronome_on_) {
            double beatFrames = 60.0 * self->sample_rate_ / (double)self->metronome_bpm_;
            int beatsPerBar = self->metronome_sig_num_;
            for (int i=0;i<numFrames;++i) {
                int64_t ph = self->position_.load() + i;
                int64_t beat = (int64_t)(ph / beatFrames);
                double frac = (ph - beat*beatFrames) / beatFrames;
                double clickEnv = exp(-frac * 40.0);
                float t = (float)sin(2.0*3.14159265*1000.0*(ph/self->sample_rate_));
                bool isDownbeat = (beat % beatsPerBar) == 0;
                float amp = (float)(isDownbeat ? clickEnv*0.6 : clickEnv*0.3);
                out[i*ch] += t*amp;
                if (ch>1) out[i*ch+1] += t*amp;
            }
        }

        // Advance position with loop
        int64_t np = self->position_.load() + numFrames;
        if (self->loop_enabled_ && self->loop_end_ > self->loop_start_ && np >= self->loop_end_) {
            np = self->loop_start_ + (np - self->loop_end_);
        }
        self->position_.store(np);

        // Metering
        float pk = 0.f, sum=0.f;
        for (int i=0;i<numFrames*ch;++i) pk = std::max(pk, fabsf(out[i]));
        for (int i=0;i<numFrames;++i){ float l=out[i*ch], r=ch>1?out[i*ch+1]:l; sum+=0.5f*(l*l+r*r); }
        self->master_peak_.store(pk);
        self->master_rms_.store((float)sqrt(sum/std::max(1,numFrames)));

        // Spectrum / LUFS
        self->spectrum_.push(out, numFrames, ch);
        // build planar ref for lufs
        std::vector<float> pl(numFrames), pr(numFrames);
        const float* planes[2] = {pl.data(), pr.data()};
        for (int i=0;i<numFrames;++i){ pl[i]=out[i*ch]; if(ch>1)pr[i]=out[i*ch+1]; else pr[i]=out[i*ch]; }
        self->lufs_.process(planes, numFrames, std::min(2, ch));
    } else {
        // Output silence
    }

    if (self->listener_) {
        static int64_t last=0; int64_t np = self->position_.load();
        if (np - last > (int64_t)(self->sample_rate_/15)) { self->listener_(self->state_.load(), np); last = np; }
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

aaudio_data_callback_result_t AudioEngine::aaudio_in_cb(AAudioStream*, void* userData, void* audioData, int32_t numFrames) {
    auto* self = static_cast<AudioEngine*>(userData);
    int ch = std::max(1, AAudioStream_getChannelCount(self->input_stream_));
    const float* in = static_cast<const float*>(audioData);
    float pk = 0.f;
    for (int i=0;i<numFrames*ch;++i) pk = std::max(pk, fabsf(in[i]*self->input_gain_));
    self->input_level_.store(pk);
    if (self->state_ == EngineState::Recording) {
        std::lock_guard<std::mutex> g(self->record_mutex_);
        size_t cur = self->record_buffer_.size();
        self->record_buffer_.resize(cur + (size_t)numFrames*2); // stereo
        for (int i=0;i<numFrames;++i) {
            self->record_buffer_[cur + i*2] = in[i*ch] * self->input_gain_;
            self->record_buffer_[cur + i*2+1] = (ch>1)? in[i*ch+1]*self->input_gain_ : in[i*ch]*self->input_gain_;
        }
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

bool AudioEngine::open_streams(float sr, int buf) {
    AAudioStreamBuilder* b;
    aaudio_result_t r = AAudio_createStreamBuilder(&b);
    if (r != AAUDIO_OK) return false;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setSampleRate(b, (int32_t)sr);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setDataCallback(b, &AudioEngine::aaudio_out_cb, this);
    r = AAudioStreamBuilder_openStream(b, &output_stream_);
    if (r != AAUDIO_OK) { LOGE("Output stream open failed: %s", AAudio_convertResultToText(r)); AAudioStreamBuilder_delete(b); return false; }
    sample_rate_ = AAudioStream_getSampleRate(output_stream_);
    buffer_size_ = AAudioStream_getBufferSizeInFrames(output_stream_);
    if (buf > 0) AAudioStream_setBufferSizeInFrames(output_stream_, buf);

    // Open input stream
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setChannelCount(b, 1);
    AAudioStreamBuilder_setDataCallback(b, &AudioEngine::aaudio_in_cb, this);
    r = AAudioStreamBuilder_openStream(b, &input_stream_);
    if (r == AAUDIO_OK) {
        input_channels_ = AAudioStream_getChannelCount(input_stream_);
        AAudioStream_requestStart(input_stream_);
    } else {
        LOGI("Input stream open failed (non-fatal): %s", AAudio_convertResultToText(r));
        input_stream_ = nullptr;
        input_channels_ = 0;
    }
    AAudioStreamBuilder_delete(b);
    return true;
}

void AudioEngine::close_streams() {
    if (output_stream_) { AAudioStream_requestStop(output_stream_); AAudioStream_close(output_stream_); output_stream_=nullptr; }
    if (input_stream_) { AAudioStream_requestStop(input_stream_); AAudioStream_close(input_stream_); input_stream_=nullptr; }
}

void AudioEngine::play() {
    state_ = EngineState::Playing;
    if (output_stream_) AAudioStream_requestStart(output_stream_);
}
void AudioEngine::pause() {
    state_ = (state_ == EngineState::Recording) ? EngineState::Recording : EngineState::Paused;
}
void AudioEngine::stop_transport() {
    state_ = EngineState::Paused;
    position_ = 0;
}
void AudioEngine::set_position(int64_t f) { position_.store(std::max<int64_t>(0,f)); }
void AudioEngine::start_recording() {
    {
        std::lock_guard<std::mutex> g(record_mutex_);
        record_buffer_.clear();
    }
    state_ = EngineState::Recording;
    if (output_stream_) AAudioStream_requestStart(output_stream_);
}
void AudioEngine::stop_recording() {
    state_ = EngineState::Paused;
    // Build AudioBuffer from record_buffer_ and add as a clip on armed track
    std::lock_guard<std::mutex> lk(engine_mutex_);
    int armedId = -1;
    for (auto& t: tracks_) if (t.arm) { armedId = t.id; break; }
    if (armedId < 0) return;
    std::vector<float> data;
    {
        std::lock_guard<std::mutex> g(record_mutex_);
        data = std::move(record_buffer_);
        record_buffer_.clear();
    }
    if (data.empty()) return;
    int64_t frames = (int64_t)data.size()/2;
    auto buf = std::make_shared<AudioBuffer>();
    buf->allocate(2, frames, sample_rate_);
    std::memcpy(buf->data_mut().data(), data.data(), data.size()*sizeof(float));
    buf->build_mipmaps();
    Clip c;
    c.id = next_clip_id_++;
    c.track_id = armedId;
    c.buffer = buf;
    c.start_frame = position_.load() - frames;
    if (c.start_frame < 0) c.start_frame = 0;
    c.length_frames = frames;
    clips_.push_back(c);
}
void AudioEngine::set_loop(int64_t s, int64_t e, bool en) { loop_start_=s; loop_end_=e; loop_enabled_=en; }

int AudioEngine::add_track(const std::string&) {
    std::lock_guard<std::mutex> lk(engine_mutex_);
    TrackConfig t; t.id = next_track_id_++; t.name = "Track " + std::to_string(t.id);
    tracks_.push_back(t);
    track_effects_.emplace_back(std::make_unique<EffectChain>());
    track_effects_.back()->set_sample_rate(sample_rate_);
    return t.id;
}
void AudioEngine::remove_track(int id) {
    std::lock_guard<std::mutex> lk(engine_mutex_);
    tracks_.erase(std::remove_if(tracks_.begin(), tracks_.end(), [&](auto& t){return t.id==id;}), tracks_.end());
}
void AudioEngine::set_track_config(int id, const TrackConfig& c) {
    std::lock_guard<std::mutex> lk(engine_mutex_);
    for (auto& t: tracks_) if (t.id==id) t=c;
}
TrackConfig AudioEngine::get_track_config(int id) const {
    std::lock_guard<std::mutex> lk(engine_mutex_);
    for (auto& t: tracks_) if (t.id==id) return t;
    return TrackConfig{};
}
TrackConfig* AudioEngine::track_ptr(int id) {
    for (auto& t: tracks_) if (t.id==id) return &t;
    return nullptr;
}
std::vector<TrackConfig> AudioEngine::tracks() const {
    std::lock_guard<std::mutex> lk(engine_mutex_);
    return tracks_;
}
int AudioEngine::add_clip(const Clip& c) {
    std::lock_guard<std::mutex> lk(engine_mutex_);
    Clip copy = c; copy.id = next_clip_id_++;
    clips_.push_back(copy); return copy.id;
}
void AudioEngine::remove_clip(int id) {
    std::lock_guard<std::mutex> lk(engine_mutex_);
    clips_.erase(std::remove_if(clips_.begin(), clips_.end(), [&](auto& c){return c.id==id;}), clips_.end());
}
std::vector<Clip> AudioEngine::clips_for_track(int trackId) const {
    std::lock_guard<std::mutex> lk(engine_mutex_);
    std::vector<Clip> r;
    for (auto& c: clips_) if (c.track_id==trackId) r.push_back(c);
    return r;
}
std::vector<Clip> AudioEngine::all_clips() const {
    std::lock_guard<std::mutex> lk(engine_mutex_);
    return clips_;
}
EffectChain* AudioEngine::track_chain(int trackId) {
    std::lock_guard<std::mutex> lk(engine_mutex_);
    for (size_t i=0;i<tracks_.size();++i) if (tracks_[i].id==trackId) return track_effects_[i].get();
    return nullptr;
}
EffectChain* AudioEngine::master_chain() { return &master_chain_; }

void AudioEngine::set_input_device(int) {}
void AudioEngine::set_input_gain(float g) { input_gain_ = g; }
void AudioEngine::arm_track(int id, bool armed, int channel) {
    std::lock_guard<std::mutex> lk(engine_mutex_);
    for (auto& t: tracks_) { if (t.id==id){t.arm=armed; t.input_channel=channel;} else if (armed) t.arm=false; }
}

void AudioEngine::get_spectrum(float* out_bins, int bins) { spectrum_.get_magnitudes(out_bins, bins); }
float AudioEngine::get_lufs_integrated() const { return lufs_.integrated(); }
float AudioEngine::get_lufs_momentary() const { return lufs_.momentary(); }
void AudioEngine::set_master_volume(float v) { master_volume_.store(v); }
void AudioEngine::set_metronome(bool on, float bpm, int sig_num, int sig_den) {
    metronome_on_=on; metronome_bpm_=bpm; metronome_sig_num_=sig_num; metronome_sig_den_=sig_den;
}

} // namespace wavestudio
