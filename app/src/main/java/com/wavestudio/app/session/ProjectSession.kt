package com.wavestudio.app.session

import android.content.Context
import android.net.Uri
import com.wavestudio.app.audio.AudioImporter
import com.wavestudio.app.data.repository.ProjectRepository
import com.wavestudio.app.domain.Project
import com.wavestudio.app.engine.AudioEngineController
import com.wavestudio.app.engine.ExportResult
import com.wavestudio.app.engine.NativeEngineApi
import com.wavestudio.app.project.ClipDoc
import com.wavestudio.app.project.EditOps
import com.wavestudio.app.project.EngineSync
import com.wavestudio.app.project.History
import com.wavestudio.app.project.NativeEffectInfo
import com.wavestudio.app.project.ProjectDoc
import com.wavestudio.app.project.ProjectStore
import com.wavestudio.app.project.TIMELINE_RATE
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

data class SessionStatus(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val projectId: Long = 0,
    val fatalError: String? = null,
    val messages: List<String> = emptyList(),      // recovery notices the user should see
    val missingMedia: Set<String> = emptySet(),
    val clipErrors: Map<Int, Int> = emptyMap(),     // clip id -> native error code
    val dirty: Boolean = false,
    val saveError: String? = null,
    val lastSavedAt: Long = 0
)

enum class RecordStart { OK, NO_INPUT, NO_TRACK, ALREADY_RECORDING, FAILED }

data class ExportOptions(val sampleRate: Int = 48000, val bitDepth: Int = 24, val channels: Int = 2, val dither: Boolean = true, val tailSeconds: Float = 0f)

sealed class ExportState {
    object Idle : ExportState()
    data class Running(val name: String) : ExportState()
    data class Done(val result: ExportResult, val name: String) : ExportState()
    data class Failed(val message: String) : ExportState()
}

/**
 * The single owner of the open project. UI edits go through [edit]; every edit updates the immutable
 * [ProjectDoc], records an undo step, pushes only the difference to the native engine and schedules an
 * atomic autosave. The native engine therefore never disagrees with what the user sees or what is saved.
 */
@Singleton
class ProjectSession @Inject constructor(
    @ApplicationContext private val ctx: Context,
    val controller: AudioEngineController,
    private val repo: ProjectRepository,
    private val importer: AudioImporter
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sync = EngineSync(NativeEngineApi(controller.native)) { rel -> store?.resolve(rel)?.absolutePath ?: rel }
    private val history = History<ProjectDoc>()
    private val lock = Any()

    @Volatile private var store: ProjectStore? = null
    private var saveJob: Job? = null
    private var exportJob: Job? = null

    private val _doc = MutableStateFlow(ProjectDoc())
    val doc: StateFlow<ProjectDoc> = _doc.asStateFlow()
    private val _status = MutableStateFlow(SessionStatus())
    val status: StateFlow<SessionStatus> = _status.asStateFlow()
    private val _undoState = MutableStateFlow(UndoState())
    val undoState: StateFlow<UndoState> = _undoState.asStateFlow()
    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _export.asStateFlow()
    private val _recordingTrack = MutableStateFlow<Int?>(null)
    val recordingTrack: StateFlow<Int?> = _recordingTrack.asStateFlow()

    data class UndoState(val canUndo: Boolean = false, val canRedo: Boolean = false, val undoLabel: String? = null, val redoLabel: String? = null)

    /** All transport/recording state changes run on one thread, in order, and never on the UI thread (they touch disk). */
    private val serial = Executors.newSingleThreadExecutor { Thread(it, "ws-transport") }.asCoroutineDispatcher()
    private val serialScope = CoroutineScope(SupervisorJob() + serial)

    @Volatile private var recWavName: String? = null
    private var recTrackId = 0

    val projectDir: File? get() = store?.dir
    fun mediaFile(rel: String): File? = store?.resolve(rel)

    // ------------------------------------------------------------------ open / close

    suspend fun open(projectId: Long) {
        if (_status.value.loaded && _status.value.projectId == projectId) return
        if (_status.value.loaded) closeCurrent()
        _status.value = SessionStatus(loading = true, projectId = projectId)
        try {
            val s = ProjectStore(File(ctx.filesDir, "projects/$projectId"))
            s.ensureDirs()
            val messages = ArrayList<String>()
            val result = withContext(Dispatchers.IO) { s.load() }
            var d = result.doc
            messages += result.warnings
            if (result.source == ProjectStore.Source.NEW) {
                val row = repo.findById(projectId)
                if (row != null) d = d.copy(name = row.name, bpm = row.bpm.coerceIn(20f, 400f), created = row.created)
                val (migrated, n) = withContext(Dispatchers.IO) { adoptLegacyImports(s, d) }
                d = migrated
                if (n > 0) messages += "Recovered $n audio file(s) imported by an earlier version of the app."
            }
            store = s
            // Interrupted recordings (process died while recording): put them back exactly where they were made.
            val (recovered, nTakes) = withContext(Dispatchers.IO) { recoverTakes(s, d) }
            d = recovered
            if (nTakes > 0) messages += "Recovered $nTakes interrupted recording(s)."
            synchronized(lock) {
                history.clear(); publishUndo()
                _doc.value = d
            }
            controller.resetLoudness()
            val report = withContext(Dispatchers.Default) { sync.sync(d, forceRebuild = true) }
            controller.setMeterTracks(d.tracks.map { it.id })
            val missing = withContext(Dispatchers.IO) { s.missingMedia(d) }
            _status.value = SessionStatus(
                loaded = true, projectId = projectId, messages = messages, missingMedia = missing,
                clipErrors = report.clipErrors, dirty = nTakes > 0 || result.source != ProjectStore.Source.PRIMARY
            )
            if (_status.value.dirty) saveNow()
        } catch (t: Throwable) {
            store = null
            _status.value = SessionStatus(projectId = projectId, fatalError = t.message ?: "Cannot open project")
        }
    }

    private fun adoptLegacyImports(s: ProjectStore, base: ProjectDoc): Pair<ProjectDoc, Int> {
        var d = base; var n = 0
        val legacy = s.dir.listFiles { f -> f.isFile && f.name.startsWith("audio_") && f.name.endsWith(".wav") }?.sortedBy { it.name } ?: return d to 0
        for (f in legacy) {
            val info = controller.mediaInfo(f.absolutePath)
            if (!info.ok || info.frames <= 0) continue
            val (d1, t) = EditOps.addTrack(d, "Recovered ${n + 1}")
            val (d2, _) = EditOps.addClip(d1, t, f.name, f.nameWithoutExtension, 0, info.timelineFrames)
            d = d2; n++
        }
        return d to n
    }

    private fun recoverTakes(s: ProjectStore, base: ProjectDoc): Pair<ProjectDoc, Int> {
        var d = base; var n = 0
        for (take in s.orphanedTakes(d)) {
            val info = controller.mediaInfo(take.wav.absolutePath)
            if (!info.ok || info.frames <= 0) continue          // leave the file alone: never delete unreadable audio
            var track = take.trackId
            if (d.track(track) == null) {
                val (d1, t) = EditOps.addTrack(d, "Recovered take"); d = d1; track = t
            }
            val (d2, id) = EditOps.addClip(d, track, "recordings/${take.wav.name}", "Recovered take", take.startFrame, info.timelineFrames)
            if (id > 0) { d = d2; n++; take.sidecar?.delete() }
        }
        return d to n
    }

    suspend fun closeCurrent() {
        if (recWavName != null) stopRecording()
        withContext(serial) { controller.pause() }
        saveJob?.cancel()
        if (store != null && _status.value.dirty) withContext(Dispatchers.IO) { saveBlocking() }
        _status.value = SessionStatus()
    }

    fun dismissMessages() { _status.update { it.copy(messages = emptyList()) } }

    // ------------------------------------------------------------------ editing

    /** Applies [f] to the document. Returns false when it changed nothing. Thread-safe. */
    fun edit(label: String, coalesceKey: String? = null, f: (ProjectDoc) -> ProjectDoc): Boolean {
        val after: ProjectDoc
        synchronized(lock) {
            val before = _doc.value
            val candidate = f(before)
            if (candidate === before) return false
            after = candidate.copy(modified = System.currentTimeMillis())
            _doc.value = after
            history.record(label, before, after, coalesceKey)
            publishUndo()
        }
        afterChange(after)
        return true
    }

    fun undo() = restore(synchronized(lock) { history.undo().also { publishUndo() } })
    fun redo() = restore(synchronized(lock) { history.redo().also { publishUndo() } })

    private fun restore(d: ProjectDoc?) {
        if (d == null) return
        synchronized(lock) { _doc.value = d }
        afterChange(d)
    }

    private fun publishUndo() {
        _undoState.value = UndoState(history.canUndo, history.canRedo, history.undoLabel, history.redoLabel)
    }

    private fun afterChange(d: ProjectDoc) {
        val report = sync.sync(d)
        controller.setMeterTracks(d.tracks.map { it.id })
        _status.update { it.copy(clipErrors = report.clipErrors, dirty = true) }
        scheduleSave()
    }

    /** Re-sends everything to the engine (e.g. after the audio device was reopened or media was relinked). */
    fun rebuildEngine() { sync.sync(_doc.value, forceRebuild = true) }

    fun nativeEffectId(docEffectId: Int): Int? = sync.nativeEffectId(docEffectId)
    fun effectInfo(docEffectId: Int): NativeEffectInfo? =
        sync.nativeEffectId(docEffectId)?.let { NativeEffectInfo.parse(controller.native.nativeEffectInfo(it)) }
    fun effectTypes(): List<String> = try {
        (com.wavestudio.app.project.Json.parse(controller.native.nativeEffectTypes()) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    } catch (_: Exception) { emptyList() }

    // ------------------------------------------------------------------ persistence

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1500)
            withContext(Dispatchers.IO) { saveBlocking() }
        }
    }

    fun saveNow() {
        saveJob?.cancel()
        scope.launch(Dispatchers.IO) { saveBlocking() }
    }

    /** Synchronous variant (call off the main thread). Used on lifecycle events and after recordings. */
    fun saveBlocking() {
        val s = store ?: return
        val d = _doc.value
        try {
            s.save(d)
            _status.update { it.copy(dirty = _doc.value !== d, saveError = null, lastSavedAt = System.currentTimeMillis()) }
            val pid = _status.value.projectId
            scope.launch {
                val row = repo.findById(pid)
                if (row != null) repo.update(row.copy(name = d.name, bpm = d.bpm, durationFrames = d.endFrame, modified = d.modified))
            }
        } catch (t: Throwable) {
            _status.update { it.copy(saveError = t.message ?: "Save failed", dirty = true) }
        }
    }

    // ------------------------------------------------------------------ import

    /** Imports a file picked via SAF and places it at [atFrame] on [trackId] (or a new track). Returns an error message or null. */
    suspend fun importAudio(uri: Uri, trackId: Int?, atFrame: Long): String? {
        val s = store ?: return "No project is open"
        return try {
            val imp = importer.import(uri, s.mediaDir)
            val length = imp.info.timelineFrames
            val rel = "media/${imp.file.name}"
            edit("Import ${imp.displayName}") { d0 ->
                var d = d0
                var tid = trackId?.takeIf { d.track(it) != null }
                if (tid == null) { val (d1, t) = EditOps.addTrack(d, imp.displayName.substringBeforeLast('.').take(24)); d = d1; tid = t }
                EditOps.addClip(d, tid, rel, imp.displayName.substringBeforeLast('.'), atFrame, length).first
            }
            saveNow()
            null
        } catch (c: kotlinx.coroutines.CancellationException) { throw c
        } catch (t: Throwable) { t.message ?: "Import failed" }
    }

    // ------------------------------------------------------------------ recording

    suspend fun startRecording(trackId: Int, bitDepth: Int = 24): RecordStart = withContext(serial) { startRecordingNow(trackId, bitDepth) }

    private fun startRecordingNow(trackId: Int, bitDepth: Int): RecordStart {
        val s = store ?: return RecordStart.FAILED
        if (recWavName != null) return RecordStart.ALREADY_RECORDING
        if (_doc.value.track(trackId) == null) return RecordStart.NO_TRACK
        controller.refreshDevice()
        val dev = controller.device.value
        if (!dev.inputRunning || dev.inputChannels < 1) return RecordStart.NO_INPUT
        val name = "take_${System.currentTimeMillis()}.wav"
        val pos = controller.position()
        s.beginTake(name, trackId, pos)                       // crash marker first: a kill mid-take is recoverable
        val path = File(s.recordingsDir, name).absolutePath
        // A loop wrap would break a take's continuity; recording runs straight through.
        controller.native.nativeSetLoop(0, 0, false)
        if (!controller.beginRecording(path, bitDepth)) {
            s.finishTake(name); File(path).delete()
            applyLoop()
            return RecordStart.FAILED
        }
        recWavName = name; recTrackId = trackId
        _recordingTrack.value = trackId
        controller.play()
        return RecordStart.OK
    }

    /** Ends the take and commits it as a clip. Returns a user-facing note (warnings), or null. */
    suspend fun stopRecording(): String? = withContext(serial) { stopRecordingNow() }

    private fun stopRecordingNow(): String? {
        val name = recWavName ?: return null
        val s = store ?: return null
        recWavName = null
        _recordingTrack.value = null
        controller.pause()
        val res = controller.endRecording()
        applyLoop()
        var note: String? = null
        if (res == null || res.frames <= 0) {
            File(s.recordingsDir, name).let { if (it.length() <= 44) it.delete() }
            s.finishTake(name)
            return if (res != null && res.ioError) "Recording failed: storage error" else null
        }
        if (res.dropped > 0) note = "Recording lost ${res.dropped} frames (the device could not keep up)."
        if (res.ioError) note = (note ?: "") + " A storage error occurred while writing; the recording was saved up to that point."
        val rel = "recordings/$name"
        val trackId = recTrackId
        edit("Record") { d0 ->
            var d = d0
            var t = trackId
            if (d.track(t) == null) { val (d1, nt) = EditOps.addTrack(d, "Recording"); d = d1; t = nt }
            EditOps.addClip(d, t, rel, "Take", res.startFrame, res.timelineFrames).first
        }
        saveBlocking()                // the take is only "committed" once the document that references it is on disk
        if (_status.value.saveError == null) s.finishTake(name)
        else note = (note ?: "") + " The project could not be saved; the recording is kept in the project's recordings folder."
        _notes.value = note?.trim()
        return note?.trim()
    }

    private val _notes = MutableStateFlow<String?>(null)
    /** Last warning produced by a take (dropped frames, storage errors); the UI shows and clears it. */
    val recordingNote: StateFlow<String?> = _notes.asStateFlow()
    fun clearRecordingNote() { _notes.value = null }

    val isRecording: Boolean get() = recWavName != null

    // Transport commands are queued so recording hand-offs are never reordered or run on the UI thread.
    fun seek(frame: Long) { serialScope.launch { if (recWavName != null) stopRecordingNow(); controller.seek(frame) } }
    fun pause() { serialScope.launch { if (recWavName != null) stopRecordingNow() else controller.pause() } }
    fun stop() { serialScope.launch { if (recWavName != null) stopRecordingNow(); controller.stopTransport() } }
    fun play() { serialScope.launch { controller.play() } }

    /** Another app took audio focus, headphones were pulled, etc. */
    fun onAudioInterruption() { pause() }

    fun applyLoop() {
        val d = _doc.value
        controller.native.nativeSetLoop(d.loopStart, d.loopEnd, d.loopEnabled)
    }

    // ------------------------------------------------------------------ export

    /** Renders the project offline (faster than real time, independent of the audio device) to [destination]. */
    fun export(destination: Uri, options: ExportOptions, displayName: String) {
        if (_export.value is ExportState.Running) return
        val d = _doc.value
        _export.value = ExportState.Running(displayName)
        exportJob = scope.launch(Dispatchers.IO) {
            val tmp = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.wav")
            try {
                val res = ExportResult.parse(
                    controller.native.nativeExport(tmp.absolutePath, options.sampleRate, options.bitDepth, options.channels, options.dither, options.tailSeconds)
                )
                if (res.ok) {
                    ctx.contentResolver.openOutputStream(destination, "wt")?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                        ?: throw java.io.IOException("Cannot write to the chosen location")
                    _export.value = ExportState.Done(res, displayName)
                } else if (res.cancelled) {
                    _export.value = ExportState.Idle
                } else {
                    _export.value = ExportState.Failed(exportErrorText(res.error, d))
                }
            } catch (t: Throwable) {
                _export.value = ExportState.Failed(t.message ?: "Export failed")
            } finally {
                tmp.delete()
            }
        }
    }

    fun exportProgress(): Float = controller.native.nativeExportProgress()
    fun cancelExport() { controller.native.nativeCancelExport() }
    fun clearExportState() { if (_export.value !is ExportState.Running) _export.value = ExportState.Idle }

    private fun exportErrorText(code: Int, d: ProjectDoc) = when (code) {
        1 -> "Nothing to export: the project has no audio"
        2 -> "These export settings are not supported"
        3 -> "Could not create the output file (storage full?)"
        5 -> "Another export is already running"
        else -> "Export failed (code $code)"
    }

    // ------------------------------------------------------------------ helpers for UI

    fun trackClips(d: ProjectDoc, trackId: Int): List<ClipDoc> = d.clips.filter { it.trackId == trackId }

    fun timelineSeconds(frames: Long): Double = frames / TIMELINE_RATE.toDouble()
}
