// JNI bridge. Every function is a thin, exception-safe wrapper over AudioDevice / EngineCore;
// no audio logic lives here. Structured results are returned as small JSON strings so the
// Kotlin side can evolve without changing native signatures.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdio>
#include <string>
#include "../dsp/audio_engine.h"
#include "../dsp/engine/offline_export.h"
#include "../dsp/effects/effect_factory.h"

#define LOG_TAG "WaveStudioJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace wavestudio;

namespace {

struct JStr {
    JNIEnv* env; jstring js; const char* c;
    JStr(JNIEnv* e, jstring s) : env(e), js(s), c(s ? e->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~JStr() { if (c) env->ReleaseStringUTFChars(js, c); }
    std::string str() const { return c ? std::string(c) : std::string(); }
};

std::string json_escape(const std::string& s) {
    std::string o;
    for (unsigned char ch : s) {
        if (ch == '"' || ch == '\\') { o += '\\'; o += (char)ch; }
        else if (ch < 0x20) { char b[8]; std::snprintf(b, sizeof b, "\\u%04x", ch); o += b; }
        else o += (char)ch;
    }
    return o;
}
std::string num(double v) {
    if (!(v == v) || v > 1e30 || v < -1e30) return "0";
    char b[48]; std::snprintf(b, sizeof b, "%.9g", v); return b;
}

jstring to_jstring(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

std::atomic<float> g_export_progress{0.f};
std::atomic<bool> g_export_cancel{false};
std::atomic<bool> g_export_running{false};

std::shared_ptr<EngineCore> C() { return AudioDevice::instance().core(); }

}  // namespace

#define JNI(ret, name, ...) extern "C" JNIEXPORT ret JNICALL Java_com_wavestudio_app_engine_NativeAudioEngine_##name(JNIEnv* env, jobject, ##__VA_ARGS__)

// ------------------------------------------------------------------ device
JNI(jboolean, nativeStart, jint preferredRate, jboolean wantInput) {
    try { return AudioDevice::instance().start(preferredRate, wantInput); } catch (...) { return false; }
}
JNI(void, nativeStop) { AudioDevice::instance().stop(); }
JNI(jboolean, nativeEnableInput, jboolean on) {
    try { return AudioDevice::instance().enable_input(on); } catch (...) { return false; }
}
JNI(void, nativeResetProject) { try { AudioDevice::instance().reset_core_for_new_project(); } catch (...) {} }
JNI(jstring, nativeDeviceStatus) {
    DeviceStatus s = AudioDevice::instance().status();
    auto c = C();
    std::string j = "{\"outputRunning\":" + std::string(s.output_running ? "true" : "false") +
        ",\"inputRunning\":" + (s.input_running ? "true" : "false") +
        ",\"sampleRate\":" + num(s.sample_rate) + ",\"inputChannels\":" + num(s.input_channels) +
        ",\"framesPerBurst\":" + num(s.frames_per_burst) + ",\"bufferFrames\":" + num(s.buffer_frames) +
        ",\"xruns\":" + num(s.xruns) + ",\"inputUnderruns\":" + num(s.input_underruns) +
        ",\"restarts\":" + num(s.restarts) + ",\"lastError\":" + num(s.last_error) +
        ",\"estOutputLatencyMs\":" + num(s.est_output_latency_ms) + ",\"estInputLatencyMs\":" + num(s.est_input_latency_ms) +
        ",\"estRoundTripFrames\":" + num(s.est_round_trip_frames) +
        ",\"engineRate\":" + num(c->engine_rate()) + ",\"underrunBlocks\":" + num((double)c->underrun_blocks()) +
        ",\"maxRenderLoad\":" + num(c->max_render_load()) + "}";
    return to_jstring(env, j);
}

// ------------------------------------------------------------------ transport
JNI(void, nativePlay) { C()->play(); }
JNI(void, nativePause) { C()->pause(); }
JNI(jboolean, nativeIsPlaying) { return C()->is_playing(); }
JNI(void, nativeSeek, jlong frame) { C()->seek(frame); }
JNI(jlong, nativeGetPosition) { return C()->position(); }
JNI(jboolean, nativeIsCountingIn) { return C()->is_counting_in(); }
JNI(void, nativeSetLoop, jlong s, jlong e, jboolean en) { C()->set_loop(s, e, en); }
JNI(void, nativeSetPunch, jboolean en, jlong in, jlong out) { C()->set_punch(en, in, out); }
JNI(void, nativeSetMetronome, jboolean on, jfloat bpm, jint beats) { C()->set_metronome(on, bpm, beats); }
JNI(void, nativeSetCountIn, jint bars) { C()->set_count_in_bars(bars); }
JNI(void, nativeSetMasterVolume, jfloat v) { C()->set_master_volume(v); }

// ------------------------------------------------------------------ model
JNI(jboolean, nativeAddTrack, jint id) { return C()->add_track(id); }
JNI(jboolean, nativeRemoveTrack, jint id) { return C()->remove_track(id); }
JNI(void, nativeSetTrackVolume, jint id, jfloat v) { C()->set_track_volume(id, v); }
JNI(void, nativeSetTrackPan, jint id, jfloat v) { C()->set_track_pan(id, v); }
JNI(void, nativeSetTrackMute, jint id, jboolean m) { C()->set_track_mute(id, m); }
JNI(void, nativeSetTrackSolo, jint id, jboolean s) { C()->set_track_solo(id, s); }

// Returns 0 / 4 (loaded, file was truncated) on success, -1 unknown track, or a MediaError code.
JNI(jint, nativeSetClip, jint id, jint trackId, jstring path, jlong start, jlong offset, jlong length,
    jfloat gainDb, jlong fadeIn, jlong fadeOut) {
    try {
        JStr p(env, path);
        ClipParams c;
        c.id = id; c.track_id = trackId; c.path = p.str(); c.start = start; c.offset = offset; c.length = length;
        c.gain_db = gainDb; c.fade_in = fadeIn; c.fade_out = fadeOut;
        return C()->set_clip(c);
    } catch (const std::exception& e) { LOGE("setClip: %s", e.what()); return kMediaOutOfMemory; }
      catch (...) { return kMediaOutOfMemory; }
}
JNI(jboolean, nativeRemoveClip, jint id) { return C()->remove_clip(id); }
JNI(jlong, nativeProjectEnd) { return C()->project_end(); }

JNI(jstring, nativeMediaInfo, jstring path) {
    JStr p(env, path);
    WavInfo info; int err = 0;
    bool ok = C()->media()->info(p.str(), info, &err);
    std::string j = std::string("{\"ok\":") + (ok ? "true" : "false") + ",\"error\":" + num(err) +
        ",\"channels\":" + num(info.channels) + ",\"frames\":" + num((double)info.frames) +
        ",\"sampleRate\":" + num(info.sample_rate) + ",\"bitDepth\":" + num(info.bit_depth) +
        ",\"float\":" + (info.is_float ? "true" : "false") + "}";
    return to_jstring(env, j);
}

JNI(jfloatArray, nativeGetPeaks, jstring path, jdouble t0, jdouble t1, jint bins) {
    if (bins < 1 || bins > 20000) return nullptr;
    JStr p(env, path);
    std::vector<float> out((size_t)bins * 2);
    C()->get_peaks(p.str(), t0, t1, bins, out.data());
    jfloatArray arr = env->NewFloatArray(bins * 2);
    if (arr) env->SetFloatArrayRegion(arr, 0, bins * 2, out.data());
    return arr;
}

// ------------------------------------------------------------------ effects
JNI(jstring, nativeEffectTypes) {
    std::string j = "[";
    bool first = true;
    for (const auto& t : available_effect_types()) { if (!first) j += ","; first = false; j += "\"" + json_escape(t) + "\""; }
    return to_jstring(env, j + "]");
}
JNI(jint, nativeAddEffect, jint trackId, jstring type, jint index) {
    try { JStr t(env, type); return C()->add_effect(trackId, t.str(), 0, index); } catch (...) { return -5; }
}
JNI(jboolean, nativeRemoveEffect, jint id) { return C()->remove_effect(id); }
JNI(jboolean, nativeMoveEffect, jint id, jint index) { return C()->move_effect(id, index); }
JNI(jboolean, nativeSetEffectParam, jint id, jint index, jfloat v) { return C()->set_effect_param(id, index, v); }
JNI(jboolean, nativeSetEffectBypass, jint id, jboolean b) { return C()->set_effect_bypass(id, b); }
JNI(jintArray, nativeListEffects, jint trackId) {
    auto ids = C()->list_effects(trackId);
    jintArray arr = env->NewIntArray((jsize)ids.size());
    if (arr && !ids.empty()) env->SetIntArrayRegion(arr, 0, (jsize)ids.size(), reinterpret_cast<const jint*>(ids.data()));
    return arr;
}
JNI(jstring, nativeEffectInfo, jint id) {
    EffectInfo e;
    if (!C()->get_effect_info(id, e)) return to_jstring(env, "{}");
    std::string j = "{\"id\":" + num(e.id) + ",\"trackId\":" + num(e.track_id) + ",\"type\":\"" + json_escape(e.type) +
        "\",\"name\":\"" + json_escape(e.name) + "\",\"bypass\":" + (e.bypass ? "true" : "false") + ",\"params\":[";
    for (size_t i = 0; i < e.params.size(); ++i) {
        const auto& p = e.params[i];
        if (i) j += ",";
        j += "{\"id\":\"" + json_escape(p.id) + "\",\"name\":\"" + json_escape(p.display) + "\",\"unit\":\"" + json_escape(p.unit) +
             "\",\"min\":" + num(p.min) + ",\"max\":" + num(p.max) + ",\"def\":" + num(p.def) + ",\"value\":" + num(p.value);
        if (!p.options.empty()) {
            j += ",\"options\":[";
            for (size_t o = 0; o < p.options.size(); ++o) j += (o ? ",\"" : "\"") + json_escape(p.options[o]) + "\"";
            j += "]";
        }
        j += "}";
    }
    return to_jstring(env, j + "]}");
}

// ------------------------------------------------------------------ recording
JNI(jboolean, nativeStartRecording, jstring path, jint bitDepth) {
    try {
        DeviceStatus s = AudioDevice::instance().status();
        if (!s.output_running || !s.input_running || s.input_channels < 1) return false;   // no real input: refuse, never fake
        JStr p(env, path);
        return C()->start_recording(p.str(), s.input_channels, bitDepth);
    } catch (...) { return false; }
}
JNI(jstring, nativeStopRecording) {
    int64_t start = 0;
    Recorder::Result r = C()->stop_recording(&start);
    std::string j = std::string("{\"ok\":") + (r.ok ? "true" : "false") + ",\"path\":\"" + json_escape(r.path) +
        "\",\"frames\":" + num((double)r.frames) + ",\"dropped\":" + num((double)r.dropped_frames) +
        ",\"channels\":" + num(r.channels) + ",\"sampleRate\":" + num(r.sample_rate) +
        ",\"ioError\":" + (r.io_error ? "true" : "false") + ",\"startFrame\":" + num((double)start) +
        ",\"engineRate\":" + num(C()->engine_rate()) + "}";
    return to_jstring(env, j);
}
JNI(jboolean, nativeIsRecording) { return C()->is_recording(); }
JNI(void, nativeSetInputLatency, jlong frames) { C()->set_input_latency_frames(frames); }
JNI(void, nativeSetMonitor, jboolean on, jfloat gain) { C()->set_monitor(on, gain); }

// ------------------------------------------------------------------ meters / analysis
// out: [masterPeakL, masterPeakR, masterRmsL, masterRmsR, inputPeak, clipCount, recordingDropped]
JNI(void, nativeGetMeters, jfloatArray out) {
    if (!out || env->GetArrayLength(out) < 7) return;
    auto c = C();
    MasterMeters m = c->master_meters();
    float v[7] = {m.peak_l, m.peak_r, m.rms_l, m.rms_r, c->input_peak(), (float)m.clip_count, (float)c->recording_dropped_frames()};
    env->SetFloatArrayRegion(out, 0, 7, v);
}
JNI(jboolean, nativeGetTrackMeter, jint id, jfloatArray out) {
    if (!out || env->GetArrayLength(out) < 2) return false;
    float l = 0, r = 0;
    bool ok = C()->track_meters(id, &l, &r);
    float v[2] = {l, r};
    env->SetFloatArrayRegion(out, 0, 2, v);
    return ok;
}
// out: [momentary, shortTerm, integrated, correlation]
JNI(void, nativeGetLoudness, jfloatArray out) {
    if (!out || env->GetArrayLength(out) < 4) return;
    auto c = C();
    float v[4] = {c->lufs_momentary(), c->lufs_short_term(), c->lufs_integrated(), c->correlation()};
    env->SetFloatArrayRegion(out, 0, 4, v);
}
JNI(void, nativeResetLoudness) { C()->reset_loudness(); }
JNI(void, nativeGetSpectrum, jfloatArray out, jint bins) {
    if (!out || bins < 2 || env->GetArrayLength(out) < bins) return;
    std::vector<float> tmp((size_t)bins);
    C()->get_spectrum(tmp.data(), bins);
    env->SetFloatArrayRegion(out, 0, bins, tmp.data());
}

// ------------------------------------------------------------------ export
JNI(jstring, nativeExport, jstring path, jint format, jint sampleRate, jint bitDepth, jint channels, jboolean dither, jfloat tailSec) {
    bool expected = false;
    if (!g_export_running.compare_exchange_strong(expected, true)) return to_jstring(env, "{\"ok\":false,\"error\":5}");   // already running
    g_export_cancel = false; g_export_progress = 0.f;
    std::string j;
    try {
        JStr p(env, path);
        ExportOptions o;
        o.path = p.str(); o.format = format; o.sample_rate = sampleRate; o.bit_depth = bitDepth; o.channels = channels;
        o.dither = dither; o.tail_seconds = tailSec;
        ExportResult r = export_project(*C(), o, &g_export_progress, &g_export_cancel);
        j = std::string("{\"ok\":") + (r.ok ? "true" : "false") + ",\"cancelled\":" + (r.cancelled ? "true" : "false") +
            ",\"error\":" + num(r.error) + ",\"frames\":" + num((double)r.frames) + ",\"peakDb\":" + num(r.peak_dbfs) +
            ",\"lufs\":" + num(r.lufs) + ",\"clipped\":" + num((double)r.clipped_samples) + "}";
    } catch (...) { j = "{\"ok\":false,\"error\":6}"; }
    g_export_running = false;
    return to_jstring(env, j);
}
JNI(void, nativeSetTrackSend, jint id, jint bus, jfloat level) { C()->set_track_send(id, bus, level); }
JNI(void, nativeSetBusLevel, jint bus, jfloat level) { C()->set_bus_level(bus, level); }

JNI(jfloat, nativeExportProgress) { return g_export_progress.load(); }
JNI(void, nativeCancelExport) { g_export_cancel = true; }
