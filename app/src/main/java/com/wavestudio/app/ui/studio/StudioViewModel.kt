package com.wavestudio.app.ui.studio

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wavestudio.app.engine.AudioEngineController
import com.wavestudio.app.project.*
import com.wavestudio.app.session.ProjectSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Plays a rendered buffer through a static [AudioTrack] (audition only; the project engine is not involved). */
class StudioPlayer {
    private var track: AudioTrack? = null
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    @Synchronized
    fun play(buf: StereoBuffer, loop: Boolean) {
        stop()
        val n = buf.frames
        if (n <= 0) return
        val data = FloatArray(n * 2)
        for (i in 0 until n) { data[2 * i] = buf.left[i]; data[2 * i + 1] = buf.right[i] }
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(Synth.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 4)
                .build()
            t.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
            if (loop) t.setLoopPoints(0, n, -1)
            else {
                t.setNotificationMarkerPosition(n - 1)
                t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack?) { if (this@StudioPlayer.track === track) _playing.value = false }
                    override fun onPeriodicNotification(track: AudioTrack?) {}
                })
            }
            track = t
            t.play()
            _playing.value = true
        } catch (_: Exception) { track?.release(); track = null; _playing.value = false }
    }

    @Synchronized
    fun stop() {
        track?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        track = null
        _playing.value = false
    }
}

@HiltViewModel
class StudioViewModel @Inject constructor(
    app: Application,
    val session: ProjectSession,
    val engine: AudioEngineController
) : AndroidViewModel(app) {

    val studio: StateFlow<StudioDoc> = session.doc.map { it.studio }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.doc.value.studio)
    val bpm: StateFlow<Float> = session.doc.map { it.bpm }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.doc.value.bpm)

    private val player = StudioPlayer()
    val playing: StateFlow<Boolean> = player.playing
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    private val noteCache = HashMap<String, StereoBuffer>()

    private fun editStudio(label: String, key: String? = null, f: (StudioDoc) -> StudioDoc) {
        session.edit(label, key) { it.copy(studio = StudioOps.normalize(f(it.studio))) }
    }

    // ---- beat
    fun cycleStep(v: DrumVoice, step: Int) = editStudio("Beat step") { it.copy(beat = StudioOps.cycleStep(it.beat, v, step)) }
    fun setBeatBars(bars: Int) = editStudio("Beat length") { it.copy(beat = StudioOps.setBars(it.beat, bars)) }
    fun setSwing(s: Float) = editStudio("Beat swing", "swing") { it.copy(beat = StudioOps.setSwing(it.beat, s)) }
    fun setMute(v: DrumVoice, mute: Boolean) = editStudio("Mute ${v.label}") { it.copy(beat = StudioOps.setRow(it.beat, v, mute = mute)) }
    fun clearBeat() = editStudio("Clear beat") { it.copy(beat = StudioOps.clearBeat(it.beat)) }
    fun demoBeat() = editStudio("Demo beat") { it.copy(beat = StudioOps.demoBeat(it.beat.bars)) }

    // ---- roll
    fun addNote(pitch: Int, step: Int, len: Int) = editStudio("Add note") { it.copy(roll = StudioOps.addNote(it.roll, pitch, step, len)) }
    fun removeNote(pitch: Int, step: Int) = editStudio("Delete note") { it.copy(roll = StudioOps.removeNoteAt(it.roll, pitch, step)) }
    fun setRollBars(bars: Int) = editStudio("Roll length") { it.copy(roll = StudioOps.setRollBars(it.roll, bars)) }
    fun setInstrument(id: String) = editStudio("Instrument") { it.copy(roll = StudioOps.setInstrument(it.roll, id)) }
    fun transpose(semis: Int) = editStudio("Transpose") { it.copy(roll = StudioOps.transpose(it.roll, semis)) }
    fun clearRoll() = editStudio("Clear roll") { it.copy(roll = StudioOps.clearRoll(it.roll)) }
    fun demoRoll() = editStudio("Demo melody") { it.copy(roll = StudioOps.demoRoll().copy(instrument = it.roll.instrument)) }

    // ---- audition
    fun previewBeat() {
        val s = studio.value; val tempo = bpm.value
        viewModelScope.launch { player.play(withContext(Dispatchers.Default) { Synth.renderBeat(s.beat, tempo, wrap = true) }, loop = true) }
    }
    fun previewRoll() {
        val s = studio.value; val tempo = bpm.value
        viewModelScope.launch { player.play(withContext(Dispatchers.Default) { Synth.renderRoll(s.roll, tempo) }, loop = false) }
    }
    fun auditionNote(pitch: Int) {
        val inst = studio.value.roll.instrument
        viewModelScope.launch {
            val buf = withContext(Dispatchers.Default) { synchronized(noteCache) { noteCache.getOrPut("$inst:$pitch") { Synth.renderPreviewNote(inst, pitch, 0.5f) } } }
            player.play(buf, loop = false)
        }
    }
    fun stopPreview() = player.stop()

    // ---- send to the project
    fun addBeatToProject() = addToProject("Beat", "Beat") { s, t -> Synth.renderBeat(s.beat, t, wrap = true) }
    fun addRollToProject() = addToProject("Piano roll", "Piano") { s, t -> Synth.renderRoll(s.roll, t) }

    private fun addToProject(name: String, track: String, render: (StudioDoc, Float) -> StereoBuffer) {
        val s = studio.value; val tempo = bpm.value
        viewModelScope.launch {
            val hasContent = if (name == "Beat") StudioOps.hitCount(s.beat) > 0 else s.roll.notes.isNotEmpty()
            if (!hasContent) { _message.value = "Nothing to add yet: the pattern is empty."; return@launch }
            _message.value = "Rendering…"
            val buf = withContext(Dispatchers.Default) { render(s, tempo) }
            val err = session.addRenderedClip(name, track, buf, engine.position())
            _message.value = err?.let { "Could not add: $it" } ?: "Added “$name” to the project as a new track at the playhead"
        }
    }

    override fun onCleared() { player.stop(); super.onCleared() }
}
