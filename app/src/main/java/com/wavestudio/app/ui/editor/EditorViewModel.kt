package com.wavestudio.app.ui.editor

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wavestudio.app.audio.AudioIO
import com.wavestudio.app.data.repository.ProjectRepository
import com.wavestudio.app.domain.*
import com.wavestudio.app.editor.UndoStack
import com.wavestudio.app.engine.AudioEngineController
import com.wavestudio.app.engine.TransportState
import com.wavestudio.app.service.AudioEngineService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    app: Application,
    private val repo: ProjectRepository,
    val engine: AudioEngineController,
    private val audioIO: AudioIO,
    private val undo: UndoStack
) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()

    private val _project = MutableStateFlow(Project(name = "Untitled", sampleRate = 48000f))
    val project: StateFlow<Project> = _project.asStateFlow()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _clips = MutableStateFlow<List<AudioClip>>(emptyList())
    val clips: StateFlow<List<AudioClip>> = _clips.asStateFlow()

    private val _markers = MutableStateFlow<List<Marker>>(emptyList())
    val markers: StateFlow<List<Marker>> = _markers.asStateFlow()

    private val _selectedClip = MutableStateFlow<Int?>(null)
    val selectedClip: StateFlow<Int?> = _selectedClip.asStateFlow()

    private val _snapEnabled = MutableStateFlow(true)
    val snapEnabled: StateFlow<Boolean> = _snapEnabled.asStateFlow()

    private val _metronome = MutableStateFlow(false)
    val metronome: StateFlow<Boolean> = _metronome.asStateFlow()

    private val _bpm = MutableStateFlow(120f)
    val bpm: StateFlow<Float> = _bpm.asStateFlow()

    private val _loop = MutableStateFlow(false)
    val loop: StateFlow<Boolean> = _loop.asStateFlow()

    val transportState: StateFlow<TransportState> = engine.transportState
    val playheadFrames: StateFlow<Long> = engine.playheadFrames
    val masterPeak: StateFlow<Float> = engine.masterPeak
    val masterRms: StateFlow<Float> = engine.masterRms
    val inputLevel: StateFlow<Float> = engine.inputLevel
    val spectrum: StateFlow<FloatArray> = engine.spectrumBins
    val lufs: StateFlow<Float> = engine.lufs

    fun loadProject(id: Long) {
        if (id == -1L) {
            viewModelScope.launch {
                val newId = repo.create(Project(name = "Untitled Project"))
                val p = repo.findById(newId) ?: return@launch
                _project.value = p
                _bpm.value = p.bpm
                ensureDefaultTrack()
            }
        } else {
            viewModelScope.launch {
                val p = repo.findById(id) ?: return@launch
                _project.value = p
                _bpm.value = p.bpm
                ensureDefaultTrack()
            }
        }
    }

    private fun ensureDefaultTrack() {
        if (_tracks.value.isEmpty()) {
            addTrack("Audio 1")
            addTrack("Audio 2")
        }
    }

    fun play() {
        ctx.startService(Intent(ctx, AudioEngineService::class.java).setAction(AudioEngineService.ACTION_PLAY))
        engine.play()
    }
    fun pause() { engine.pause() }
    fun stop() {
        ctx.startService(Intent(ctx, AudioEngineService::class.java).setAction(AudioEngineService.ACTION_STOP))
        engine.stopTransport()
    }
    fun record() {
        ctx.startService(Intent(ctx, AudioEngineService::class.java).setAction(AudioEngineService.ACTION_RECORD))
        engine.startRecording()
    }
    fun seek(f: Long) { engine.seek(f) }

    fun addTrack(name: String = "Track") {
        val id = engine.addTrack(name)
        val t = Track(id = id, name = name, color = trackPalette[(_tracks.value.size % trackPalette.size)])
        _tracks.value = _tracks.value + t
    }

    fun removeTrack(id: Int) {
        _tracks.value = _tracks.value.filter { it.id != id }
    }

    fun setTrackVolume(id: Int, v: Float) {
        _tracks.value = _tracks.value.map { if (it.id == id) it.copy(volume = v) else it }
        engine.setTrackVolume(id, v)
    }
    fun setTrackPan(id: Int, p: Float) {
        _tracks.value = _tracks.value.map { if (it.id == id) it.copy(pan = p) else it }
        engine.setTrackPan(id, p)
    }
    fun setTrackMute(id: Int, m: Boolean) {
        _tracks.value = _tracks.value.map { if (it.id == id) it.copy(mute = m) else it }
        engine.setTrackMute(id, m)
    }
    fun setTrackSolo(id: Int, s: Boolean) {
        _tracks.value = _tracks.value.map { if (it.id == id) it.copy(solo = s) else it }
        engine.setTrackSolo(id, s)
    }
    fun setTrackArm(id: Int, a: Boolean) {
        _tracks.value = _tracks.value.map { if (it.id == id) it.copy(arm = a) else it }
        engine.setTrackArm(id, a)
    }
    fun renameTrack(id: Int, name: String) {
        _tracks.value = _tracks.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    fun selectClip(id: Int?) { _selectedClip.value = id }
    fun moveClip(id: Int, newStart: Long, newTrack: Int) {
        _clips.value = _clips.value.map { if (it.id == id) it.copy(startFrame = newStart, trackId = newTrack) else it }
    }

    fun setSnap(enabled: Boolean) { _snapEnabled.value = enabled }
    fun setMetronome(on: Boolean) {
        _metronome.value = on
        engine.setMetronome(on, _bpm.value)
    }
    fun setBpm(bpm: Float) {
        _bpm.value = bpm
        if (_metronome.value) engine.setMetronome(true, bpm)
    }

    fun toggleLoop() {
        _loop.value = !_loop.value
        // set default loop around view if enabling
        if (_loop.value) engine.setLoop(0L, (60 * project.value.sampleRate).toLong(), true)
        else engine.setLoop(0,0,false)
    }

    fun setMasterVolume(v: Float) = engine.setMasterVolume(v)

    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            val loaded = audioIO.loadAudio(uri) ?: return@launch
            // Copy to project folder and add clip to first available track
            val projDir = File(ctx.filesDir, "projects/${_project.value.id}")
            projDir.mkdirs()
            val dst = File(projDir, "audio_${System.currentTimeMillis()}.wav")
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { i ->
                    dst.outputStream().use { o -> i.copyTo(o) }
                }
            }
            val trackId = _tracks.value.firstOrNull()?.id ?: addTrack("Audio 1").let { 1 /* engine returns first track id */ }
            val startFrame = engine.playheadFrames.value
            val clip = AudioClip(
                id = _clips.value.size + 1,
                trackId = trackId,
                sourcePath = dst.absolutePath,
                startFrame = startFrame,
                lengthFrames = loaded.frames,
                name = dst.nameWithoutExtension
            )
            _clips.value = _clips.value + clip
            engine.loadWav(dst.absolutePath)
        }
    }

    fun export(path: String, format: String = "wav", onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = audioIO.exportWav(_project.value, File(path), 24)
            onDone(ok)
        }
    }

    fun undo() { undo.undo() }
    fun redo() { undo.redo() }

    fun save() {
        viewModelScope.launch {
            val p = _project.value.copy(
                modified = System.currentTimeMillis(),
                durationFrames = _clips.value.maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L,
                bpm = _bpm.value
            )
            repo.update(p)
            _project.value = p
        }
    }

    override fun onCleared() { save(); super.onCleared() }

    companion object {
        private val trackPalette = listOf(
            0xFF4A90E2UL, 0xFFE27A4AUL, 0xFF9A4AE2UL, 0xFF4AE299UL,
            0xFFE2D64AUL, 0xFFE24A88UL, 0xFF4AD7E2UL, 0xFFFF8A3DUL
        )
    }
}
