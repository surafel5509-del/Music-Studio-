package com.wavestudio.app.ui.editor

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wavestudio.app.data.repository.ProjectRepository
import com.wavestudio.app.domain.Project
import com.wavestudio.app.engine.AudioEngineController
import com.wavestudio.app.engine.TransportState
import com.wavestudio.app.project.ClipDoc
import com.wavestudio.app.project.EditOps
import com.wavestudio.app.project.MAX_BUSES
import com.wavestudio.app.project.ProjectDoc
import com.wavestudio.app.project.TIMELINE_RATE
import com.wavestudio.app.project.TrackDoc
import com.wavestudio.app.service.AudioEngineService
import com.wavestudio.app.service.ExportService
import com.wavestudio.app.session.ExportOptions
import com.wavestudio.app.session.ExportState
import com.wavestudio.app.session.ProjectSession
import com.wavestudio.app.session.RecordStart
import com.wavestudio.app.session.SessionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Waveform overview of one media file: min/max pairs, [binsPerSecond] bins per second of source audio. */
class PeakData(val minMax: FloatArray, val binsPerSecond: Float) {
    val bins get() = minMax.size / 2
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    app: Application,
    private val repo: ProjectRepository,
    val session: ProjectSession,
    val engine: AudioEngineController
) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()

    val doc: StateFlow<ProjectDoc> = session.doc
    val status: StateFlow<SessionStatus> = session.status
    val undoState = session.undoState
    val exportState: StateFlow<ExportState> = session.exportState
    val recordingTrack = session.recordingTrack

    val tracks: StateFlow<List<TrackDoc>> = doc.map { it.tracks }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val clips: StateFlow<List<ClipDoc>> = doc.map { it.clips }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val transportState = engine.transportState
    val playheadFrames = engine.playheadFrames
    val meters = engine.meters
    val trackMeters = engine.trackMeters
    val spectrum = engine.spectrum
    val loudness = engine.loudness
    val device = engine.device
    val countingIn = engine.countingIn

    private val _selectedClip = MutableStateFlow<Int?>(null)
    val selectedClip: StateFlow<Int?> = _selectedClip.asStateFlow()
    private val _armedTrack = MutableStateFlow<Int?>(null)
    val armedTrack: StateFlow<Int?> = _armedTrack.asStateFlow()
    private val _snap = MutableStateFlow(true)
    val snapEnabled: StateFlow<Boolean> = _snap.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _peaks = MutableStateFlow<Map<String, PeakData>>(emptyMap())
    val peaks: StateFlow<Map<String, PeakData>> = _peaks.asStateFlow()
    private val peakRequests = HashSet<String>()

    fun consumeMessage() { _message.value = null }
    private fun say(m: String) { _message.value = m }

    // ------------------------------------------------------------------ project lifecycle

    fun loadProject(id: Long) {
        viewModelScope.launch {
            var pid = id
            if (pid == -1L) pid = repo.create(Project(name = "Untitled Project"))
            session.open(pid)
            val d = session.doc.value
            if (session.status.value.loaded && d.tracks.isEmpty() && d.clips.isEmpty()) {
                session.edit("New project") { EditOps.addTrack(it, "Audio 1").first }
            }
        }
    }

    fun save() { session.saveNow() }

    override fun onCleared() {
        // The session is app-scoped and keeps the document; make sure the last edits are on disk.
        session.saveNow()
        super.onCleared()
    }

    // ------------------------------------------------------------------ transport

    fun play() { AudioEngineService.start(ctx); session.play() }
    fun pause() { session.pause() }
    fun stop() { session.stop() }
    fun seek(frame: Long) { session.seek(frame.coerceAtLeast(0)) }

    fun toggleRecord() {
        viewModelScope.launch {
            if (session.isRecording) {
                session.stopRecording()?.let { say(it) }
                return@launch
            }
            var tid = _armedTrack.value?.takeIf { id -> session.doc.value.track(id) != null } ?: session.doc.value.tracks.firstOrNull()?.id
            if (tid == null) { session.edit("Add track") { d -> EditOps.addTrack(d).first }; tid = session.doc.value.tracks.first().id }
            var r = session.startRecording(tid)
            if (r == RecordStart.NO_INPUT && hasMic()) { engine.enableInput(); r = session.startRecording(tid) }
            when (r) {
                RecordStart.OK -> AudioEngineService.start(ctx)
                RecordStart.NO_INPUT -> say(if (hasMic()) "No audio input is available on this device." else "Microphone permission is required to record. Grant it in system settings.")
                RecordStart.NO_TRACK -> say("Select a track to record on.")
                RecordStart.ALREADY_RECORDING -> {}
                RecordStart.FAILED -> say("Recording could not be started.")
            }
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val recordingNote = session.recordingNote
    fun dismissRecordingNote() = session.clearRecordingNote()

    // ------------------------------------------------------------------ tracks

    fun addTrack() { session.edit("Add track") { EditOps.addTrack(it).first } }
    fun removeTrack(id: Int) { session.edit("Remove track") { EditOps.removeTrack(it, id) }; if (_armedTrack.value == id) _armedTrack.value = null }
    fun renameTrack(id: Int, name: String) { session.edit("Rename track") { EditOps.updateTrack(it, id) { t -> t.copy(name = name.take(40)) } } }
    fun setTrackVolume(id: Int, v: Float) { session.edit("Volume", "vol:$id") { EditOps.updateTrack(it, id) { t -> t.copy(volume = v.coerceIn(0f, 4f)) } } }
    fun setTrackPan(id: Int, p: Float) { session.edit("Pan", "pan:$id") { EditOps.updateTrack(it, id) { t -> t.copy(pan = p.coerceIn(-1f, 1f)) } } }
    fun toggleMute(id: Int) { session.edit("Mute") { EditOps.updateTrack(it, id) { t -> t.copy(mute = !t.mute) } } }
    fun toggleSolo(id: Int) { session.edit("Solo") { EditOps.updateTrack(it, id) { t -> t.copy(solo = !t.solo) } } }
    fun toggleArm(id: Int) { _armedTrack.value = if (_armedTrack.value == id) null else id }
    fun addBus() { if (!session.edit("Add aux bus") { EditOps.addBus(it).first }) say("All $MAX_BUSES aux buses are in use") }
    fun removeBus(index: Int) { session.edit("Remove aux bus") { EditOps.removeBus(it, index) } }
    fun setBusLevel(index: Int, v: Float) { session.edit("Bus level", "bus:$index") { EditOps.setBusLevel(it, index, v) } }
    fun setTrackSend(trackId: Int, bus: Int, v: Float) { session.edit("Send level", "send:$trackId:$bus") { EditOps.setTrackSend(it, trackId, bus, v) } }
    fun setMasterVolume(v: Float) { session.edit("Master volume", "master") { it.copy(masterVolume = v.coerceIn(0f, 2f)) } }

    // ------------------------------------------------------------------ clips

    fun selectClip(id: Int?) { _selectedClip.value = id }
    fun setSnap(on: Boolean) { _snap.value = on }

    private fun grid(d: ProjectDoc): Double = d.framesPerBeat / 4.0     // 16th-note grid
    private fun snap(d: ProjectDoc, f: Long) = EditOps.snap(f, grid(d), _snap.value)

    /** Called continuously while dragging: [newStart]/[newTrack] are applied (snapped) and merged into a single undo step. */
    fun moveClip(id: Int, newStart: Long, newTrack: Int?) {
        session.edit("Move clip", "move:$id") { EditOps.moveClip(it, id, snap(it, newStart), newTrack) }
    }

    fun trimClipStart(id: Int, newStart: Long) { session.edit("Trim clip", "trim:$id") { EditOps.trimClip(it, id, newStart = snap(it, newStart)) } }
    fun trimClipEnd(id: Int, newEnd: Long) { session.edit("Trim clip", "trim:$id") { EditOps.trimClip(it, id, newEnd = snap(it, newEnd)) } }

    fun splitAtPlayhead() {
        val id = _selectedClip.value ?: return say("Select a clip to split.")
        val at = engine.position()
        var created = -1
        session.edit("Split clip") { d -> val (n, nid) = EditOps.splitClip(d, id, at); created = nid; n }
        if (created < 0) say("Place the playhead inside the selected clip to split it.")
    }

    fun deleteSelectedClip() {
        val id = _selectedClip.value ?: return
        session.edit("Delete clip") { EditOps.deleteClip(it, id) }
        _selectedClip.value = null
    }

    fun duplicateSelectedClip() {
        val id = _selectedClip.value ?: return
        var created = -1
        session.edit("Duplicate clip") { d -> val (n, nid) = EditOps.duplicateClip(d, id); created = nid; n }
        if (created > 0) _selectedClip.value = created
    }

    fun setClipGain(id: Int, db: Float) { session.edit("Clip gain", "cgain:$id") { EditOps.setClipGain(it, id, db) } }
    fun setClipFades(id: Int, fadeIn: Long, fadeOut: Long) { session.edit("Clip fades", "cfade:$id") { EditOps.setClipFades(it, id, fadeIn, fadeOut) } }

    /** Sets the clip gain so its peak reaches -1 dBFS (non-destructive; the audio file is untouched). */
    fun normalizeSelectedClip() {
        val id = _selectedClip.value ?: return
        viewModelScope.launch {
            val peak = session.clipPeak(id)
            if (peak == null) { say("Could not read the audio of this clip."); return@launch }
            val g = EditOps.normalizeGainDb(peak, -1f)
            if (g == null) { say("This clip is silent; nothing to normalize."); return@launch }
            session.edit("Normalize clip") { EditOps.setClipGain(it, id, g) }
            say("Clip gain set to %+.1f dB".format(g))
        }
    }

    // ------------------------------------------------------------------ markers and punch

    fun addMarkerAtPlayhead() {
        val at = engine.position()
        session.edit("Add marker") { EditOps.addMarker(it, at).first }
    }
    fun renameMarker(id: Int, name: String) { session.edit("Rename marker") { EditOps.renameMarker(it, id, name) } }
    fun deleteMarker(id: Int) { session.edit("Delete marker") { EditOps.removeMarker(it, id) } }
    fun jumpToMarker(frame: Long) = seek(frame)
    fun seekNextMarker() { EditOps.nextMarker(session.doc.value, engine.position())?.let { seek(it.frame) } ?: say("No later marker.") }
    fun seekPrevMarker() { EditOps.prevMarker(session.doc.value, engine.position())?.let { seek(it.frame) } ?: seek(0L) }

    fun setPunchInAtPlayhead() = setPunchPoint(isIn = true)
    fun setPunchOutAtPlayhead() = setPunchPoint(isIn = false)
    private fun setPunchPoint(isIn: Boolean) {
        val at = engine.position()
        val d = session.doc.value
        val (i, o) = if (isIn) at to (if (d.punchOut > at + EditOps.MIN_CLIP) d.punchOut else at + 5L * TIMELINE_RATE)
                     else (if (d.punchIn < at - EditOps.MIN_CLIP) d.punchIn else (at - 5L * TIMELINE_RATE).coerceAtLeast(0)) to at
        if (!session.edit("Punch range") { EditOps.setPunchRange(it, i, o) }) say("Punch range not changed (out must be after in).")
    }
    fun togglePunch() {
        val d = session.doc.value
        if (!session.edit("Punch") { EditOps.setPunchEnabled(it, !d.punchEnabled) } && !d.punchEnabled)
            say("Set punch in and out points first (move the playhead, then use Punch in / Punch out).")
    }

    // ------------------------------------------------------------------ arrangement

    fun setBpm(bpm: Float) { session.edit("Tempo", "bpm") { it.copy(bpm = bpm.coerceIn(20f, 400f)) } }
    fun setMetronome(on: Boolean) { session.edit("Metronome") { it.copy(metronome = on) } }
    fun setCountIn(bars: Int) { session.edit("Count-in") { it.copy(countInBars = bars.coerceIn(0, 4)) } }

    fun toggleLoop() {
        val playhead = engine.position()
        session.edit("Loop") { d ->
            if (d.loopEnabled) d.copy(loopEnabled = false)
            else {
                var s = d.loopStart; var e = d.loopEnd
                if (e <= s) {
                    val c = _selectedClip.value?.let { d.clip(it) }
                    if (c != null) { s = c.startFrame; e = c.endFrame }
                    else { s = snap(d, playhead); e = s + (d.framesPerBeat * d.beatsPerBar * 2).toLong() }
                }
                d.copy(loopStart = s, loopEnd = e, loopEnabled = true)
            }
        }
    }
    fun setLoopRegion(start: Long, end: Long) {
        if (end - start < 480) return
        session.edit("Loop region", "loopreg") { d -> d.copy(loopStart = snap(d, start).coerceAtLeast(0), loopEnd = snap(d, end), loopEnabled = true) }
    }

    // ------------------------------------------------------------------ files

    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            say("Importing…")
            val target = _armedTrack.value ?: session.doc.value.tracks.firstOrNull()?.id
            val err = session.importAudio(uri, target, snap(session.doc.value, engine.position()))
            say(if (err != null) "Import failed: $err" else "Import finished")
        }
    }

    fun export(uri: Uri, name: String, options: ExportOptions) {
        ExportService.start(ctx)
        session.export(uri, options, name)
    }
    fun cancelExport() = session.cancelExport()
    fun dismissExport() = session.clearExportState()

    fun undo() { session.undo() }
    fun redo() { session.redo() }
    fun renameProject(name: String) { session.edit("Rename project") { it.copy(name = name.take(60)) } }
    fun dismissSessionMessages() = session.dismissMessages()

    // ------------------------------------------------------------------ waveforms

    /** Computes (once) the waveform overview of a media file with the native engine, off the UI thread. */
    fun ensurePeaks(media: String) {
        if (!peakRequests.add(media)) return
        viewModelScope.launch(Dispatchers.IO) {
            val f = session.mediaFile(media) ?: return@launch
            val info = engine.mediaInfo(f.absolutePath)
            if (!info.ok || info.sampleRate <= 0) return@launch
            val seconds = info.frames.toDouble() / info.sampleRate
            val perSec = 50f
            val bins = (seconds * perSec).toInt().coerceIn(64, 20000)
            val data = engine.peaks(f.absolutePath, 0.0, seconds, bins) ?: return@launch
            _peaks.value = _peaks.value + (media to PeakData(data, (bins / seconds).toFloat()))
        }
    }

    init {
        viewModelScope.launch { clips.collect { list -> list.forEach { ensurePeaks(it.media) } } }
    }

    companion object { const val RATE = TIMELINE_RATE.toFloat() }
}
