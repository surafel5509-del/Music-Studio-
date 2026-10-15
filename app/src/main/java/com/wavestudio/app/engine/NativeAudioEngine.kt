package com.wavestudio.app.engine

/**
 * JNI bridge to the native C++ audio engine built with AAudio/OpenSLES.
 * All methods are thread-safe and can be called from UI or worker threads.
 */
class NativeAudioEngine {

    var sampleRate: Float = 48000f
        private set
    var bufferSize: Int = 512
        private set

    fun start(sampleRate: Float = 48000f, bufferSize: Int = 0): Boolean {
        this.sampleRate = sampleRate
        val ok = nativeStart(sampleRate, bufferSize)
        if (ok) {
            // Set up default master chain with limiter on native side via effect adds from Kotlin.
        }
        return ok
    }

    fun stop() = nativeStop()
    fun play() = nativePlay()
    fun pause() = nativePause()
    fun setPosition(frame: Long) = nativeSetPosition(frame)
    fun getPosition(): Long = nativeGetPosition()

    fun addTrack(name: String): Int = nativeAddTrack(name)
    fun setTrackVolume(id: Int, vol: Float) = nativeSetTrackVolume(id, vol)
    fun setTrackPan(id: Int, pan: Float) = nativeSetTrackPan(id, pan)
    fun setTrackMute(id: Int, mute: Boolean) = nativeSetTrackMute(id, mute)
    fun setTrackSolo(id: Int, solo: Boolean) = nativeSetTrackSolo(id, solo)
    fun setTrackArm(id: Int, arm: Boolean) = nativeSetTrackArm(id, arm)
    fun setMasterVolume(v: Float) = nativeSetMasterVolume(v)

    fun getMasterPeak(): Float = nativeGetMasterPeak()
    fun getMasterRms(): Float = nativeGetMasterRms()
    fun getInputLevel(): Float = nativeGetInputLevel()

    fun startRecording() = nativeStartRecording()
    fun stopRecording() = nativeStopRecording()

    fun loadWav(path: String): Int = nativeLoadWav(path)
    fun saveWav(path: String, data: FloatArray, channels: Int, frames: Int, sr: Float): Boolean =
        nativeSaveWav(path, data, channels, frames, sr)

    fun getSpectrum(outBins: FloatArray) = nativeGetSpectrum(outBins, outBins.size)
    fun getLufs(): Float = nativeGetLufs()
    fun setMetronome(on: Boolean, bpm: Float, num: Int = 4, den: Int = 4) =
        nativeSetMetronome(on, bpm, num, den)
    fun setLoop(start: Long, end: Long, enabled: Boolean) = nativeSetLoop(start, end, enabled)

    // External native methods implemented in jni/native_lib.cpp via CMake.
    private external fun nativeStart(sr: Float, buf: Int): Boolean
    private external fun nativeStop()
    private external fun nativePlay()
    private external fun nativePause()
    private external fun nativeSetPosition(frame: Long)
    private external fun nativeGetPosition(): Long
    private external fun nativeAddTrack(name: String): Int
    private external fun nativeSetTrackVolume(id: Int, vol: Float)
    private external fun nativeSetTrackPan(id: Int, pan: Float)
    private external fun nativeSetTrackMute(id: Int, mute: Boolean)
    private external fun nativeSetTrackSolo(id: Int, solo: Boolean)
    private external fun nativeSetTrackArm(id: Int, arm: Boolean)
    private external fun nativeSetMasterVolume(v: Float)
    private external fun nativeGetMasterPeak(): Float
    private external fun nativeGetMasterRms(): Float
    private external fun nativeGetInputLevel(): Float
    private external fun nativeStartRecording()
    private external fun nativeStopRecording()
    private external fun nativeLoadWav(path: String): Int
    private external fun nativeSaveWav(path: String, data: FloatArray, ch: Int, frames: Int, sr: Float): Boolean
    private external fun nativeGetSpectrum(outBins: FloatArray, bins: Int)
    private external fun nativeGetLufs(): Float
    private external fun nativeSetMetronome(on: Boolean, bpm: Float, num: Int, den: Int)
    private external fun nativeSetLoop(s: Long, e: Long, en: Boolean)

    companion object {
        init {
            System.loadLibrary("wavestudio")
        }
    }
}
