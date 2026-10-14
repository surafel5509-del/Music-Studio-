package com.wavestudio.app.engine

/**
 * Thin JNI bridge to the C++ engine (app/src/main/cpp/jni/native_lib.cpp is the source of truth).
 * All calls are non-blocking for the audio thread: the engine communicates with its callback
 * through lock-free queues, so these may be called from any thread. [nativeExport] blocks until
 * the offline render ends: call it from a background thread only.
 */
class NativeAudioEngine {
    // ---- device
    external fun nativeStart(preferredRate: Int, wantInput: Boolean): Boolean
    external fun nativeStop()
    external fun nativeEnableInput(on: Boolean): Boolean
    external fun nativeResetProject()
    external fun nativeDeviceStatus(): String

    // ---- transport
    external fun nativePlay()
    external fun nativePause()
    external fun nativeIsPlaying(): Boolean
    external fun nativeSeek(frame: Long)
    external fun nativeGetPosition(): Long
    external fun nativeIsCountingIn(): Boolean
    external fun nativeSetLoop(start: Long, end: Long, enabled: Boolean)
    external fun nativeSetPunch(enabled: Boolean, inFrame: Long, outFrame: Long)
    external fun nativeSetMetronome(on: Boolean, bpm: Float, beats: Int)
    external fun nativeSetCountIn(bars: Int)
    external fun nativeSetMasterVolume(v: Float)

    // ---- tracks and clips
    external fun nativeAddTrack(id: Int): Boolean
    external fun nativeRemoveTrack(id: Int): Boolean
    external fun nativeSetTrackVolume(id: Int, v: Float)
    external fun nativeSetTrackPan(id: Int, v: Float)
    external fun nativeSetTrackMute(id: Int, m: Boolean)
    external fun nativeSetTrackSend(id: Int, bus: Int, level: Float)
    external fun nativeSetBusLevel(bus: Int, level: Float)
    external fun nativeSetTrackSolo(id: Int, s: Boolean)
    external fun nativeSetClip(id: Int, trackId: Int, path: String, start: Long, offset: Long, length: Long,
                               gainDb: Float, fadeIn: Long, fadeOut: Long): Int
    external fun nativeRemoveClip(id: Int): Boolean
    external fun nativeProjectEnd(): Long
    external fun nativeMediaInfo(path: String): String
    external fun nativeGetPeaks(path: String, t0: Double, t1: Double, bins: Int): FloatArray?

    // ---- effects
    external fun nativeEffectTypes(): String
    external fun nativeAddEffect(trackId: Int, type: String, index: Int): Int
    external fun nativeRemoveEffect(id: Int): Boolean
    external fun nativeMoveEffect(id: Int, index: Int): Boolean
    external fun nativeSetEffectParam(id: Int, index: Int, v: Float): Boolean
    external fun nativeSetEffectBypass(id: Int, bypass: Boolean): Boolean
    external fun nativeListEffects(trackId: Int): IntArray?
    external fun nativeEffectInfo(id: Int): String

    // ---- recording
    external fun nativeStartRecording(path: String, bitDepth: Int): Boolean
    external fun nativeStopRecording(): String
    external fun nativeIsRecording(): Boolean
    external fun nativeSetInputLatency(frames: Long)
    external fun nativeSetMonitor(on: Boolean, gain: Float)

    // ---- metering / analysis
    external fun nativeGetMeters(out: FloatArray)
    external fun nativeGetTrackMeter(id: Int, out: FloatArray): Boolean
    external fun nativeGetLoudness(out: FloatArray)
    external fun nativeResetLoudness()
    external fun nativeGetSpectrum(out: FloatArray, bins: Int)

    // ---- export
    external fun nativeExport(path: String, format: Int, sampleRate: Int, bitDepth: Int, channels: Int, dither: Boolean, tailSec: Float): String
    external fun nativeExportProgress(): Float
    external fun nativeCancelExport()

    companion object {
        init { System.loadLibrary("wavestudio") }
    }
}
