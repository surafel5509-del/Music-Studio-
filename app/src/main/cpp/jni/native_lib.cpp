#include <jni.h>
#include <android/log.h>
#include "../dsp/audio_engine.h"
#include "../dsp/file_io/wav_reader.h"
#include "../dsp/effects/eq.h"
#include "../dsp/effects/compressor.h"
#include "../dsp/effects/reverb.h"
#include "../dsp/effects/distortion.h"
#include "../dsp/effects/pitch_shift.h"

#define LOG_TAG "WaveStudioJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

using namespace wavestudio;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeStart(JNIEnv*, jobject, jfloat sr, jint buf) {
    return AudioEngine::instance().start(sr, buf);
}

JNIEXPORT void JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeStop(JNIEnv*, jobject) {
    AudioEngine::instance().stop();
}

JNIEXPORT void JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativePlay(JNIEnv*, jobject) {
    AudioEngine::instance().play();
}

JNIEXPORT void JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativePause(JNIEnv*, jobject) {
    AudioEngine::instance().pause();
}

JNIEXPORT void JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeSetPosition(JNIEnv*, jobject, jlong frame) {
    AudioEngine::instance().set_position(frame);
}

JNIEXPORT jlong JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeGetPosition(JNIEnv*, jobject) {
    return AudioEngine::instance().position();
}

JNIEXPORT jint JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeAddTrack(JNIEnv* env, jobject, jstring name) {
    const char* n = name ? env->GetStringUTFChars(name, nullptr) : "Track";
    int id = AudioEngine::instance().add_track(n ? n : "Track");
    if (name && n) env->ReleaseStringUTFChars(name, n);
    return id;
}

JNIEXPORT void JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeSetTrackVolume(JNIEnv*, jobject, jint id, jfloat vol) {
    auto* t = AudioEngine::instance().track_ptr(id);
    if (t) t->volume = vol;
}

JNIEXPORT void JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeSetTrackPan(JNIEnv*, jobject, jint id, jfloat pan) {
    auto* t = AudioEngine::instance().track_ptr(id);
    if (t) t->pan = pan;
}

JNIEXPORT void JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeSetTrackMute(JNIEnv*, jobject, jint id, jboolean m) {
    auto* t = AudioEngine::instance().track_ptr(id);
    if (t) t->mute = m;
}

JNIEXPORT void JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeSetTrackSolo(JNIEnv*, jobject, jint id, jboolean s) {
    auto* t = AudioEngine::instance().track_ptr(id);
    if (t) t->solo = s;
}

JNIEXPORT void JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeSetTrackArm(JNIEnv*, jobject, jint id, jboolean a) {
    AudioEngine::instance().arm_track(id, a, 0);
}

JNIEXPORT void JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeSetMasterVolume(JNIEnv*, jobject, jfloat v) {
    AudioEngine::instance().set_master_volume(v);
}

JNIEXPORT jfloat JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeGetMasterPeak(JNIEnv*, jobject) {
    return AudioEngine::instance().master_peak();
}

JNIEXPORT jfloat JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeGetMasterRms(JNIEnv*, jobject) {
    return AudioEngine::instance().master_rms();
}

JNIEXPORT jfloat JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeGetInputLevel(JNIEnv*, jobject) {
    return AudioEngine::instance().input_level();
}

JNIEXPORT void JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeStartRecording(JNIEnv*, jobject) {
    AudioEngine::instance().start_recording();
}

JNIEXPORT void JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeStopRecording(JNIEnv*, jobject) {
    AudioEngine::instance().stop_recording();
}

JNIEXPORT jint JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeLoadWav(JNIEnv* env, jobject, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    auto buf = WavReader::load(path);
    env->ReleaseStringUTFChars(jpath, path);
    if (!buf) return -1;
    // Add to a new track's clip via AudioEngine
    int tid = AudioEngine::instance().add_track("Audio");
    Clip c;
    c.track_id = tid;
    c.buffer = buf;
    c.start_frame = 0;
    c.offset_frame = 0;
    c.length_frames = buf->frames();
    int cid = AudioEngine::instance().add_clip(c);
    LOGD("Loaded wav: tid=%d cid=%d frames=%lld sr=%.1f", tid, cid, (long long)buf->frames(), buf->sample_rate());
    return tid;
}

JNIEXPORT jboolean JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeSaveWav(JNIEnv* env, jobject, jstring jpath, jfloatArray data, jint ch, jint frames, jfloat sr) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    jfloat* arr = env->GetFloatArrayElements(data, nullptr);
    bool ok = WavWriter::save_interleaved(path, arr, ch, frames, sr, 24);
    env->ReleaseFloatArrayElements(data, arr, JNI_ABORT);
    env->ReleaseStringUTFChars(jpath, path);
    return ok;
}

JNIEXPORT void JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeGetSpectrum(JNIEnv* env, jobject, jfloatArray outBins, jint bins) {
    jfloat* arr = env->GetFloatArrayElements(outBins, nullptr);
    AudioEngine::instance().get_spectrum(arr, bins);
    env->ReleaseFloatArrayElements(outBins, arr, 0);
}

JNIEXPORT jfloat JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeGetLufs(JNIEnv*, jobject) {
    return AudioEngine::instance().get_lufs_integrated();
}

JNIEXPORT void JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeSetMetronome(JNIEnv*, jobject, jboolean on, jfloat bpm, jint num, jint den) {
    AudioEngine::instance().set_metronome(on, bpm, num, den);
}

JNIEXPORT void JNICALL
Java_com_wavestudio_app_engine_NativeAudioEngine_nativeSetLoop(JNIEnv*, jobject, jlong s, jlong e, jboolean en) {
    AudioEngine::instance().set_loop(s, e, en);
}

} // extern "C"
