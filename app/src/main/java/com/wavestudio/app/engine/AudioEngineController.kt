package com.wavestudio.app.engine

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

enum class TransportState { STOPPED, PLAYING, RECORDING, PAUSED }

/**
 * Higher-level controller wrapping the native engine with Kotlin Flows, transport state,
 * and coroutine-based polling for meters. Used by ViewModels and UI.
 */
@Singleton
class AudioEngineController @Inject constructor(
    private val native: NativeAudioEngine
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _transportState = MutableStateFlow(TransportState.STOPPED)
    val transportState: StateFlow<TransportState> = _transportState.asStateFlow()

    private val _playheadFrames = MutableStateFlow(0L)
    val playheadFrames: StateFlow<Long> = _playheadFrames.asStateFlow()

    private val _masterPeak = MutableStateFlow(0f)
    val masterPeak: StateFlow<Float> = _masterPeak.asStateFlow()

    private val _masterRms = MutableStateFlow(0f)
    val masterRms: StateFlow<Float> = _masterRms.asStateFlow()

    private val _inputLevel = MutableStateFlow(0f)
    val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    private val _spectrumBins = MutableStateFlow(FloatArray(64) { -80f })
    val spectrumBins: StateFlow<FloatArray> = _spectrumBins.asStateFlow()

    private val _lufs = MutableStateFlow(-60f)
    val lufs: StateFlow<Float> = _lufs.asStateFlow()

    private val _sampleRate = MutableStateFlow(48000f)
    val sampleRate: StateFlow<Float> = _sampleRate.asStateFlow()

    private var pollJob: Job? = null

    fun start(sampleRate: Float = 48000f, bufferSize: Int = 0) {
        val ok = native.start(sampleRate, bufferSize)
        if (ok) {
            _sampleRate.value = native.sampleRate
            startPolling()
        }
    }

    fun stop() {
        pollJob?.cancel()
        native.stop()
        _transportState.value = TransportState.STOPPED
    }

    fun play() {
        native.play()
        _transportState.value = TransportState.PLAYING
    }

    fun pause() {
        native.pause()
        _transportState.value = TransportState.PAUSED
    }

    fun stopTransport() {
        native.pause()
        native.setPosition(0L)
        _playheadFrames.value = 0L
        _transportState.value = TransportState.STOPPED
    }

    fun seek(frame: Long) {
        native.setPosition(frame)
        _playheadFrames.value = frame
    }

    fun startRecording() {
        native.startRecording()
        _transportState.value = TransportState.RECORDING
    }

    fun stopRecording() {
        native.stopRecording()
        _transportState.value = TransportState.PAUSED
    }

    fun setMasterVolume(v: Float) = native.setMasterVolume(v)
    fun setTrackVolume(id: Int, v: Float) = native.setTrackVolume(id, v)
    fun setTrackPan(id: Int, v: Float) = native.setTrackPan(id, v)
    fun setTrackMute(id: Int, m: Boolean) = native.setTrackMute(id, m)
    fun setTrackSolo(id: Int, s: Boolean) = native.setTrackSolo(id, s)
    fun setTrackArm(id: Int, a: Boolean) = native.setTrackArm(id, a)
    fun addTrack(name: String): Int = native.addTrack(name)
    fun loadWav(path: String): Int = native.loadWav(path)
    fun setMetronome(on: Boolean, bpm: Float, num: Int = 4, den: Int = 4) =
        native.setMetronome(on, bpm, num, den)
    fun setLoop(s: Long, e: Long, en: Boolean) = native.setLoop(s, e, en)

    fun framesToTimeString(frames: Long): String {
        val totalSec = frames / _sampleRate.value.coerceAtLeast(1f)
        val m = (totalSec / 60).toInt()
        val s = (totalSec % 60).toInt()
        val ms = ((totalSec - (m*60 + s)) * 1000).toInt()
        return "%02d:%02d.%03d".format(m, s, ms)
    }

    fun timeToFrames(seconds: Float): Long = (seconds * _sampleRate.value).toLong()

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            val bins = FloatArray(64)
            while (isActive) {
                _playheadFrames.value = native.getPosition()
                _masterPeak.value = native.getMasterPeak()
                _masterRms.value = native.getMasterRms()
                _inputLevel.value = native.getInputLevel()
                native.getSpectrum(bins)
                _spectrumBins.value = bins.copyOf()
                _lufs.value = native.getLufs()
                delay(33L) // ~30fps for meters
            }
        }
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
