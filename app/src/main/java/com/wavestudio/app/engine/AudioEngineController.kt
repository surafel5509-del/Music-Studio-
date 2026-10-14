package com.wavestudio.app.engine

import com.wavestudio.app.project.Json
import com.wavestudio.app.project.JsonException
import com.wavestudio.app.project.TIMELINE_RATE
import com.wavestudio.app.project.bool
import com.wavestudio.app.project.float
import com.wavestudio.app.project.int
import com.wavestudio.app.project.long
import com.wavestudio.app.project.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class TransportState { STOPPED, PLAYING, RECORDING, PAUSED }

data class DeviceStatus(
    val outputRunning: Boolean = false, val inputRunning: Boolean = false, val sampleRate: Int = 0,
    val inputChannels: Int = 0, val framesPerBurst: Int = 0, val bufferFrames: Int = 0,
    val xruns: Int = 0, val inputUnderruns: Int = 0, val restarts: Int = 0, val lastError: Int = 0,
    val estOutputLatencyMs: Float = 0f, val estInputLatencyMs: Float = 0f, val estRoundTripFrames: Int = 0,
    val underrunBlocks: Long = 0, val maxRenderLoad: Float = 0f
) {
    companion object {
        fun parse(json: String): DeviceStatus = try {
            val m = Json.parseObject(json)
            DeviceStatus(m.bool("outputRunning"), m.bool("inputRunning"), m.int("sampleRate"), m.int("inputChannels"),
                m.int("framesPerBurst"), m.int("bufferFrames"), m.int("xruns"), m.int("inputUnderruns"), m.int("restarts"),
                m.int("lastError"), m.float("estOutputLatencyMs"), m.float("estInputLatencyMs"), m.int("estRoundTripFrames"),
                m.long("underrunBlocks"), m.float("maxRenderLoad"))
        } catch (_: JsonException) { DeviceStatus() }
    }
}

data class MasterMeters(
    val peakL: Float = 0f, val peakR: Float = 0f, val rmsL: Float = 0f, val rmsR: Float = 0f,
    val inputPeak: Float = 0f, val clipCount: Int = 0, val recordingDropped: Long = 0
) { val peak get() = maxOf(peakL, peakR); val rms get() = maxOf(rmsL, rmsR) }

/** Momentary / short-term / integrated LUFS and L/R correlation. */
data class Loudness(val momentary: Float = -70f, val shortTerm: Float = -70f, val integrated: Float = -70f, val correlation: Float = 0f)

data class RecordingResult(
    val ok: Boolean, val path: String, val frames: Long, val dropped: Long, val channels: Int,
    val sampleRate: Int, val ioError: Boolean, val startFrame: Long
) {
    /** Length on the 48 kHz project timeline. */
    val timelineFrames: Long get() = if (sampleRate > 0) frames * TIMELINE_RATE / sampleRate else frames
    companion object {
        fun parse(json: String): RecordingResult? = try {
            val m = Json.parseObject(json)
            RecordingResult(m.bool("ok"), m.str("path"), m.long("frames"), m.long("dropped"), m.int("channels"),
                m.int("sampleRate"), m.bool("ioError"), m.long("startFrame"))
        } catch (_: JsonException) { null }
    }
}

data class MediaInfo(val ok: Boolean, val error: Int, val channels: Int, val frames: Long, val sampleRate: Int, val bitDepth: Int) {
    val timelineFrames: Long get() = if (sampleRate > 0) frames * TIMELINE_RATE / sampleRate else 0
    companion object {
        fun parse(json: String): MediaInfo = try {
            val m = Json.parseObject(json)
            MediaInfo(m.bool("ok"), m.int("error"), m.int("channels"), m.long("frames"), m.int("sampleRate"), m.int("bitDepth"))
        } catch (_: JsonException) { MediaInfo(false, -1, 0, 0, 0, 0) }
    }
}

data class ExportResult(val ok: Boolean, val cancelled: Boolean, val error: Int, val frames: Long, val peakDb: Float, val lufs: Float, val clipped: Boolean) {
    companion object {
        fun parse(json: String): ExportResult = try {
            val m = Json.parseObject(json)
            ExportResult(m.bool("ok"), m.bool("cancelled"), m.int("error"), m.long("frames"), m.float("peakDb"), m.float("lufs"), m.bool("clipped"))
        } catch (_: JsonException) { ExportResult(false, false, 6, 0, 0f, 0f, false) }
    }
}

/**
 * Owns the audio device lifecycle, transport and the polled read-outs (meters, position, device health).
 * Everything the UI shows here comes from the native engine; nothing is simulated.
 * Positions are frames on the 48 kHz project timeline regardless of the device rate.
 */
@Singleton
class AudioEngineController @Inject constructor(val native: NativeAudioEngine) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _transportState = MutableStateFlow(TransportState.STOPPED)
    val transportState: StateFlow<TransportState> = _transportState.asStateFlow()
    private val _playheadFrames = MutableStateFlow(0L)
    val playheadFrames: StateFlow<Long> = _playheadFrames.asStateFlow()
    private val _meters = MutableStateFlow(MasterMeters())
    val meters: StateFlow<MasterMeters> = _meters.asStateFlow()
    private val _trackMeters = MutableStateFlow<Map<Int, Pair<Float, Float>>>(emptyMap())
    val trackMeters: StateFlow<Map<Int, Pair<Float, Float>>> = _trackMeters.asStateFlow()
    private val _spectrum = MutableStateFlow(FloatArray(64) { -90f })
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()
    private val _loudness = MutableStateFlow(Loudness())
    val loudness: StateFlow<Loudness> = _loudness.asStateFlow()
    private val _device = MutableStateFlow(DeviceStatus())
    val device: StateFlow<DeviceStatus> = _device.asStateFlow()
    private val _countingIn = MutableStateFlow(false)
    val countingIn: StateFlow<Boolean> = _countingIn.asStateFlow()

    /** Project timeline rate (constant). Device rate is in [device]. */
    val sampleRate: StateFlow<Float> = MutableStateFlow(TIMELINE_RATE.toFloat()).asStateFlow()

    @Volatile private var started = false
    @Volatile private var uiVisible = true
    @Volatile private var meterTrackIds: IntArray = IntArray(0)
    private var pollJob: Job? = null

    val isStarted get() = started

    /** Opens the audio device. Safe to call repeatedly. [wantInput] opens the microphone stream (needs RECORD_AUDIO). */
    @Synchronized
    fun start(wantInput: Boolean): Boolean {
        if (started) {
            if (wantInput && !_device.value.inputRunning) native.nativeEnableInput(true)
            refreshDevice()
            return true
        }
        val ok = native.nativeStart(0, wantInput)
        refreshDevice()
        if (ok) { started = true; startPolling() }
        return ok
    }

    /** Called when RECORD_AUDIO is granted after the engine already runs. */
    fun enableInput(): Boolean { val ok = native.nativeEnableInput(true); refreshDevice(); return ok }

    @Synchronized
    fun stop() {
        pollJob?.cancel(); pollJob = null
        if (started) native.nativeStop()
        started = false
        _transportState.value = TransportState.STOPPED
    }

    fun refreshDevice() {
        val d = DeviceStatus.parse(native.nativeDeviceStatus())
        _device.value = d
        if (d.estRoundTripFrames > 0) native.nativeSetInputLatency(d.estRoundTripFrames.toLong())
    }

    fun setUiVisible(v: Boolean) { uiVisible = v }
    fun setMeterTracks(ids: List<Int>) { meterTrackIds = ids.toIntArray() }

    // ---- transport
    fun play() { native.nativePlay(); updateTransport() }
    fun pause() { native.nativePause(); updateTransport() }
    fun stopTransport() { native.nativePause(); native.nativeSeek(0L); _playheadFrames.value = 0L; updateTransport() }
    fun seek(frame: Long) { val f = frame.coerceAtLeast(0L); native.nativeSeek(f); _playheadFrames.value = f }
    fun position(): Long = native.nativeGetPosition()
    fun isPlaying(): Boolean = native.nativeIsPlaying()

    // ---- recording
    fun beginRecording(path: String, bitDepth: Int = 24): Boolean = native.nativeStartRecording(path, bitDepth)
    fun endRecording(): RecordingResult? = RecordingResult.parse(native.nativeStopRecording())
    fun isRecording(): Boolean = native.nativeIsRecording()
    fun setMonitor(on: Boolean, gain: Float = 1f) = native.nativeSetMonitor(on, gain)

    fun mediaInfo(path: String): MediaInfo = MediaInfo.parse(native.nativeMediaInfo(path))
    fun resetLoudness() = native.nativeResetLoudness()

    /** Waveform min/max pairs for [bins] bins over the source range, or null if the file cannot be decoded. */
    fun peaks(path: String, t0: Double, t1: Double, bins: Int): FloatArray? = native.nativeGetPeaks(path, t0, t1, bins)

    private fun updateTransport() {
        val rec = native.nativeIsRecording()
        val playing = native.nativeIsPlaying()
        _transportState.value = when {
            rec -> TransportState.RECORDING
            playing -> TransportState.PLAYING
            _playheadFrames.value <= 0L -> TransportState.STOPPED
            else -> TransportState.PAUSED
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            val m = FloatArray(7); val tm = FloatArray(2); val ld = FloatArray(4); val bins = FloatArray(64)
            var tick = 0
            while (isActive) {
                if (!uiVisible) { updateTransport(); delay(250); continue }
                _playheadFrames.value = native.nativeGetPosition()
                _countingIn.value = native.nativeIsCountingIn()
                updateTransport()
                native.nativeGetMeters(m)
                _meters.value = MasterMeters(m[0], m[1], m[2], m[3], m[4], m[5].toInt(), m[6].toLong())
                val ids = meterTrackIds
                if (ids.isNotEmpty()) {
                    val map = HashMap<Int, Pair<Float, Float>>(ids.size * 2)
                    for (id in ids) if (native.nativeGetTrackMeter(id, tm)) map[id] = tm[0] to tm[1]
                    _trackMeters.value = map
                }
                if (tick % 2 == 0) { native.nativeGetSpectrum(bins, 64); _spectrum.value = bins.copyOf() }
                if (tick % 6 == 0) {
                    native.nativeGetLoudness(ld)
                    _loudness.value = Loudness(ld[0], ld[1], ld[2], ld[3])
                }
                if (tick % 30 == 0) _device.value = DeviceStatus.parse(native.nativeDeviceStatus())
                tick++
                delay(33L)
            }
        }
    }

    fun framesToTimeString(frames: Long): String {
        val totalSec = frames / TIMELINE_RATE.toDouble()
        val m = (totalSec / 60).toInt(); val s = (totalSec % 60).toInt()
        val ms = ((totalSec - (m * 60 + s)) * 1000).toInt()
        return "%02d:%02d.%03d".format(m, s, ms)
    }
}
