package com.wavestudio.app.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wavestudio.app.engine.MasterMeters
import com.wavestudio.app.engine.TransportState
import com.wavestudio.app.project.TIMELINE_RATE
import com.wavestudio.app.project.TrackDoc
import com.wavestudio.app.session.ExportOptions
import com.wavestudio.app.session.ExportState
import com.wavestudio.app.ui.common.ToolbarButton
import com.wavestudio.app.ui.common.TransportButton
import com.wavestudio.app.ui.common.VuMeter
import com.wavestudio.app.ui.components.SpectrumAnalyzerView
import com.wavestudio.app.ui.mixer.MixerPanel
import com.wavestudio.app.ui.theme.WaveStudioTheme
import com.wavestudio.app.ui.timeline.TIMELINE_RULER_HEIGHT_DP
import com.wavestudio.app.ui.timeline.TIMELINE_TRACK_HEIGHT_DP
import com.wavestudio.app.ui.timeline.TimelineView
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.log10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    projectId: Long,
    onBack: () -> Unit,
    onOpenMixer: () -> Unit,
    onOpenEffects: (Int) -> Unit,
    onOpenPlugins: () -> Unit,
    vm: EditorViewModel = hiltViewModel()
) {
    val doc by vm.doc.collectAsState()
    val status by vm.status.collectAsState()
    val undoState by vm.undoState.collectAsState()
    val selectedClip by vm.selectedClip.collectAsState()
    val armed by vm.armedTrack.collectAsState()
    val snap by vm.snapEnabled.collectAsState()
    val transport by vm.transportState.collectAsState()
    val playhead by vm.playheadFrames.collectAsState()
    val meters by vm.meters.collectAsState()
    val trackMeters by vm.trackMeters.collectAsState()
    val spectrum by vm.spectrum.collectAsState()
    val loudness by vm.loudness.collectAsState()
    val device by vm.device.collectAsState()
    val peaks by vm.peaks.collectAsState()
    val recordingTrack by vm.recordingTrack.collectAsState()
    val counting by vm.countingIn.collectAsState()
    val message by vm.message.collectAsState()
    val recNote by vm.recordingNote.collectAsState()
    val exportState by vm.exportState.collectAsState()

    val config = LocalConfiguration.current
    val isWide = config.screenWidthDp >= 600
    val rate = TIMELINE_RATE.toFloat()

    var zoom by rememberSaveable { mutableStateOf(100f) }
    val scrollY = remember { mutableFloatStateOf(0f) }
    var showMixer by rememberSaveable { mutableStateOf(false) }
    var showAnalyzer by rememberSaveable { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var renameTrack by remember { mutableStateOf<TrackDoc?>(null) }
    var pendingExport by remember { mutableStateOf<ExportOptions?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(projectId) { vm.loadProject(projectId) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(recNote) { recNote?.let { snackbar.showSnackbar(it); vm.dismissRecordingNote() } }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importAudio(it) } }
    val createExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val o = pendingExport
        if (uri != null && o != null) vm.export(uri, doc.name, o)
        pendingExport = null
    }

    val saveLabel = when {
        status.saveError != null -> "SAVE FAILED"
        status.dirty -> "Saving…"
        else -> "Saved"
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(doc.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                        val rateTxt = if (device.sampleRate > 0) "${device.sampleRate / 1000f} kHz" else "audio off"
                        Text("$rateTxt · ${doc.tracks.size} tracks · xruns ${device.xruns} · $saveLabel", fontSize = 10.sp,
                            color = if (status.saveError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = { vm.save(); onBack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { vm.undo() }, enabled = undoState.canUndo) { Icon(Icons.Default.Undo, "Undo ${undoState.undoLabel ?: ""}") }
                    IconButton(onClick = { vm.redo() }, enabled = undoState.canRedo) { Icon(Icons.Default.Redo, "Redo ${undoState.redoLabel ?: ""}") }
                    IconButton(onClick = { pickAudio.launch(arrayOf("audio/*")) }) { Icon(Icons.Default.FolderOpen, "Import audio") }
                    IconButton(onClick = { showExport = true }) { Icon(Icons.Default.Download, "Export mix") }
                    IconButton(onClick = onOpenPlugins) { Icon(Icons.Default.Extension, "Effects list") }
                }
            )
        },
        bottomBar = {
            TransportBar(
                transport = transport, countingIn = counting, playhead = playhead, bpm = doc.bpm, beatsPerBar = doc.beatsPerBar,
                metronome = doc.metronome, loop = doc.loopEnabled, countInBars = doc.countInBars, meters = meters,
                onPlay = vm::play, onPause = vm::pause, onStop = vm::stop, onRecord = vm::toggleRecord,
                onRtz = { vm.seek(0L) },
                onToggleMetronome = { vm.setMetronome(!doc.metronome) },
                onToggleLoop = vm::toggleLoop,
                onCycleCountIn = { vm.setCountIn((doc.countInBars + 1) % 3) }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // ---- banners: real problems with this project, never hidden
            if (status.fatalError != null) {
                Banner("Project could not be opened: ${status.fatalError}", error = true)
            }
            if (status.missingMedia.isNotEmpty())
                Banner("${status.missingMedia.size} audio file(s) are missing from the project folder. Affected clips are red and silent.", error = true)
            if (status.clipErrors.isNotEmpty())
                Banner("${status.clipErrors.size} clip(s) could not be loaded into the engine (unsupported format or file too large).", error = true)
            if (status.saveError != null) Banner("Autosave failed: ${status.saveError}", error = true)
            if (recordingTrack != null && meters.recordingDropped > 0) Banner("Recording is dropping audio (${meters.recordingDropped} frames).", error = true)

            // ---- edit toolbar (scrolls horizontally on narrow screens)
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    ToolbarButton(Icons.Default.GridOn, "Snap", onClick = { vm.setSnap(!snap) }, active = snap)
                    ToolbarButton(Icons.Default.Tune, "Mixer", onClick = { showMixer = !showMixer }, active = showMixer)
                    ToolbarButton(Icons.Default.Timer, "Meters", onClick = { showAnalyzer = !showAnalyzer }, active = showAnalyzer)
                    ToolbarButton(Icons.Default.ZoomOut, "Zoom −", onClick = { zoom = (zoom / 1.5f).coerceAtLeast(5f) })
                    ToolbarButton(Icons.Default.ZoomIn, "Zoom +", onClick = { zoom = (zoom * 1.5f).coerceAtMost(4000f) })
                    Text("BPM", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                    BpmStepper(bpm = doc.bpm, onValue = vm::setBpm)
                    if (selectedClip != null) {
                        Spacer(Modifier.width(8.dp))
                        ToolbarButton(Icons.Default.ContentCut, "Split", onClick = vm::splitAtPlayhead)
                        ToolbarButton(Icons.Default.ContentCopy, "Duplicate", onClick = vm::duplicateSelectedClip)
                        ToolbarButton(Icons.Default.Delete, "Delete", onClick = vm::deleteSelectedClip)
                    }
                }
            }
            val sel = selectedClip?.let { doc.clip(it) }
            if (sel != null) ClipInspector(
                name = sel.name, gainDb = sel.gainDb, fadeInMs = sel.fadeInFrames * 1000f / rate, fadeOutMs = sel.fadeOutFrames * 1000f / rate,
                onGain = { vm.setClipGain(sel.id, it) },
                onFadeIn = { vm.setClipFades(sel.id, (it * rate / 1000f).toLong(), sel.fadeOutFrames) },
                onFadeOut = { vm.setClipFades(sel.id, sel.fadeInFrames, (it * rate / 1000f).toLong()) }
            )

            // ---- timeline with track headers
            val headerW = if (isWide) 176.dp else 124.dp
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(headerW).fillMaxHeight()) {
                    Spacer(Modifier.height(TIMELINE_RULER_HEIGHT_DP.dp))
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        Column(Modifier.offset { IntOffset(0, -scrollY.floatValue.toInt()) }) {
                            doc.tracks.forEach { t ->
                                TrackHeader(
                                    track = t, armed = armed == t.id, recording = recordingTrack == t.id,
                                    meterPeak = trackMeters[t.id]?.let { maxOf(it.first, it.second) } ?: 0f,
                                    onMute = { vm.toggleMute(t.id) }, onSolo = { vm.toggleSolo(t.id) },
                                    onArm = { vm.toggleArm(t.id) }, onEffects = { onOpenEffects(t.id) },
                                    onName = { renameTrack = t }
                                )
                            }
                            TextButton(onClick = vm::addTrack, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add track")
                            }
                        }
                    }
                }
                val projectEnd = doc.endFrame
                TimelineView(
                    tracks = doc.tracks, clips = doc.clips, peaks = peaks, missingMedia = status.missingMedia,
                    playheadFrames = playhead,
                    totalFrames = max(max(projectEnd, playhead) + (30L * TIMELINE_RATE), 120L * TIMELINE_RATE),
                    bpm = doc.bpm, beatsPerBar = doc.beatsPerBar,
                    loopEnabled = doc.loopEnabled, loopStart = doc.loopStart, loopEnd = doc.loopEnd,
                    selectedClipId = selectedClip, recordingTrackId = recordingTrack,
                    zoom = zoom, scrollYState = scrollY, onZoomChange = { zoom = it },
                    onSeek = vm::seek, onSelectClip = vm::selectClip,
                    onMoveClip = vm::moveClip, onTrimStart = vm::trimClipStart, onTrimEnd = vm::trimClipEnd,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }

            if (showAnalyzer) {
                Row(modifier = Modifier.height(110.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                    SpectrumAnalyzerView(bins = spectrum, modifier = Modifier.weight(1f).fillMaxHeight())
                    Column(modifier = Modifier.width(150.dp).fillMaxHeight().padding(6.dp)) {
                        Text("LUFS  M ${fmtLufs(loudness.momentary)}  S ${fmtLufs(loudness.shortTerm)}", fontSize = 10.sp)
                        Text("Integrated ${fmtLufs(loudness.integrated)}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Phase corr. %.2f".format(loudness.correlation), fontSize = 10.sp)
                        Text("Render load ${(device.maxRenderLoad * 100).toInt()}%  late blocks ${device.underrunBlocks}", fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (showMixer) {
                Box(Modifier.fillMaxWidth().height(230.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    MixerPanel(
                        tracks = doc.tracks, trackMeters = trackMeters, master = meters, masterVolume = doc.masterVolume,
                        onVolume = vm::setTrackVolume, onPan = vm::setTrackPan, onMute = vm::toggleMute, onSolo = vm::toggleSolo,
                        onMasterVolume = vm::setMasterVolume, onEffects = onOpenEffects, horizontal = true
                    )
                }
            }
        }
    }

    // ---- dialogs
    if (status.messages.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = vm::dismissSessionMessages,
            title = { Text("Project notices") },
            text = { Column { status.messages.forEach { Text("• $it", fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp)) } } },
            confirmButton = { TextButton(onClick = vm::dismissSessionMessages) { Text("OK") } }
        )
    }
    renameTrack?.let { t ->
        var name by remember(t.id) { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = { renameTrack = null },
            title = { Text("Track") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { vm.renameTrack(t.id, name.ifBlank { t.name }); renameTrack = null }) { Text("Rename") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.removeTrack(t.id); renameTrack = null }) { Text("Delete track", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { renameTrack = null }) { Text("Cancel") }
                }
            }
        )
    }
    if (showExport) {
        ExportDialog(
            onDismiss = { showExport = false },
            onExport = { o ->
                showExport = false
                pendingExport = o
                createExport.launch(doc.name.replace(Regex("[^A-Za-z0-9._-]+"), "_") + ".wav")
            }
        )
    }
    ExportStatusDialog(vm, exportState)
}

private fun fmtLufs(v: Float) = if (v <= -69.9f) "—" else "%.1f".format(v)

@Composable
private fun Banner(text: String, error: Boolean) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun BpmStepper(bpm: Float, onValue: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onValue((bpm - 1f).coerceAtLeast(20f)) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Remove, "Slower", modifier = Modifier.size(16.dp))
        }
        Text("%.0f".format(bpm), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
        IconButton(onClick = { onValue((bpm + 1f).coerceAtMost(400f)) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Add, "Faster", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ClipInspector(name: String, gainDb: Float, fadeInMs: Float, fadeOutMs: Float,
                          onGain: (Float) -> Unit, onFadeIn: (Float) -> Unit, onFadeOut: (Float) -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.width(90.dp))
            LabeledSlider("Gain %.1f dB".format(gainDb), gainDb, -24f..12f, onGain)
            LabeledSlider("Fade in %.0f ms".format(fadeInMs), fadeInMs, 0f..2000f, onFadeIn)
            LabeledSlider("Fade out %.0f ms".format(fadeOutMs), fadeOutMs, 0f..2000f, onFadeOut)
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.width(150.dp)) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun TrackHeader(
    track: TrackDoc, armed: Boolean, recording: Boolean, meterPeak: Float,
    onMute: () -> Unit, onSolo: () -> Unit, onArm: () -> Unit, onEffects: () -> Unit, onName: () -> Unit
) {
    val c = Color(track.color)
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().height(TIMELINE_TRACK_HEIGHT_DP.dp).padding(vertical = 1.dp)) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(c, RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f)) {
                Text(track.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.clickable(onClick = onName))
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    MiniToggle("M", track.mute, MaterialTheme.colorScheme.error, onMute)
                    MiniToggle("S", track.solo, MaterialTheme.colorScheme.tertiary, onSolo)
                    IconToggleButton(checked = armed, onCheckedChange = { onArm() }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Mic, "Arm for recording", modifier = Modifier.size(16.dp),
                            tint = if (recording) Color.Red else if (armed) Color(0xFFFF6A6A) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onEffects, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Tune, "Effects", modifier = Modifier.size(16.dp),
                            tint = if (track.effects.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // real per-track peak (read from the engine's track meter)
                val db = if (meterPeak < 1e-5f) -90f else 20f * log10(meterPeak)
                LinearProgressIndicator(progress = { ((db + 60f) / 60f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = if (db > -3f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MiniToggle(text: String, on: Boolean, onColor: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).padding(2.dp).clip(RoundedCornerShape(4.dp))
            .background(if (on) onColor else MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
fun TransportBar(
    transport: TransportState, countingIn: Boolean, playhead: Long, bpm: Float, beatsPerBar: Int,
    metronome: Boolean, loop: Boolean, countInBars: Int, meters: MasterMeters,
    onPlay: () -> Unit, onPause: () -> Unit, onStop: () -> Unit, onRecord: () -> Unit, onRtz: () -> Unit,
    onToggleMetronome: () -> Unit, onToggleLoop: () -> Unit, onCycleCountIn: () -> Unit
) {
    val clipColor = MaterialTheme.colorScheme.error
    Surface(tonalElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val recording = transport == TransportState.RECORDING
            IconButton(
                onClick = onRecord,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(if (recording) Color.Red else MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(Icons.Default.FiberManualRecord, if (recording) "Stop recording" else "Record",
                    tint = if (recording) Color.White else Color(0xFFE53935))
            }
            TransportButton(Icons.Default.Stop, onClick = onStop, contentDescription = "Stop")
            if (transport == TransportState.PLAYING || recording) TransportButton(Icons.Default.Pause, active = true, onClick = onPause, contentDescription = "Pause")
            else TransportButton(Icons.Default.PlayArrow, onClick = onPlay, contentDescription = "Play")
            TransportButton(Icons.Default.SkipPrevious, onClick = onRtz, contentDescription = "Return to start")

            Spacer(Modifier.width(6.dp))
            Surface(color = Color.Black, shape = RoundedCornerShape(4.dp)) {
                val sec = playhead / TIMELINE_RATE.toDouble()
                val m = (sec / 60).toInt(); val s = (sec % 60).toInt(); val cs = ((sec - floor(sec)) * 100).toInt()
                val secPerBeat = 60.0 / bpm.coerceAtLeast(1f)
                val beatIdx = (sec / secPerBeat).toInt()
                Column(Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) {
                    Text(if (countingIn) "COUNT-IN" else "%02d:%02d.%02d".format(m, s, cs), color = Color(0xFF43E97B),
                        fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("bar ${beatIdx / beatsPerBar + 1} · beat ${beatIdx % beatsPerBar + 1}", color = Color(0xFF43E97B).copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.width(6.dp))
            Row(Modifier.height(40.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                VuMeter(peak = meters.peakL, rms = meters.rmsL, modifier = Modifier.size(14.dp, 40.dp))
                VuMeter(peak = meters.peakR, rms = meters.rmsR, modifier = Modifier.size(14.dp, 40.dp))
                VuMeter(peak = meters.inputPeak, rms = meters.inputPeak, modifier = Modifier.size(14.dp, 40.dp), label = "IN")
            }
            if (meters.clipCount > 0) Text("CLIP", color = clipColor, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            ToolbarButton(Icons.Default.Repeat, "Loop", active = loop, onClick = onToggleLoop)
            ToolbarButton(Icons.Default.Timer, "Click", active = metronome, onClick = onToggleMetronome)
            ToolbarButton(Icons.Default.Timer, if (countInBars == 0) "No count" else "Count $countInBars", active = countInBars > 0, onClick = onCycleCountIn)
        }
    }
}

@Composable
private fun ExportDialog(onDismiss: () -> Unit, onExport: (ExportOptions) -> Unit) {
    var rate by remember { mutableStateOf(48000) }
    var bits by remember { mutableStateOf(24) }
    var channels by remember { mutableStateOf(2) }
    var dither by remember { mutableStateOf(true) }
    var tail by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export mix (WAV)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Renders the project offline through the same engine, tracks, effects and master as playback.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Sample rate", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(44100, 48000, 96000).forEach { r -> FilterChip(selected = rate == r, onClick = { rate = r }, label = { Text("${r / 1000f} kHz") }) }
                }
                Text("Bit depth", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(16, 24, 32).forEach { b -> FilterChip(selected = bits == b, onClick = { bits = b }, label = { Text(if (b == 32) "32-bit float" else "$b-bit") }) }
                }
                Text("Channels", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = channels == 2, onClick = { channels = 2 }, label = { Text("Stereo") })
                    FilterChip(selected = channels == 1, onClick = { channels = 1 }, label = { Text("Mono") })
                }
                if (bits == 16) Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = dither, onCheckedChange = { dither = it }); Spacer(Modifier.width(8.dp)); Text("Dither (16-bit)", fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = tail, onCheckedChange = { tail = it }); Spacer(Modifier.width(8.dp)); Text("Include 3 s effect tail", fontSize = 12.sp)
                }
                Text("No loudness normalization is applied.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onExport(ExportOptions(rate, bits, channels, dither && bits == 16, if (tail) 3f else 0f)) }) { Text("Choose file…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ExportStatusDialog(vm: EditorViewModel, state: ExportState) {
    when (state) {
        is ExportState.Idle -> {}
        is ExportState.Running -> {
            var progress by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(state) {
                while (true) { progress = vm.session.exportProgress(); kotlinx.coroutines.delay(200) }
            }
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Exporting…") },
                text = { Column { Text(state.name); Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) } },
                confirmButton = { TextButton(onClick = vm::cancelExport) { Text("Cancel") } }
            )
        }
        is ExportState.Done -> AlertDialog(
            onDismissRequest = vm::dismissExport,
            title = { Text("Export finished") },
            text = {
                Column {
                    val r = state.result
                    Text("Peak %.1f dBFS · %.1f LUFS integrated".format(r.peakDb, r.lufs))
                    if (r.clipped) Text("The mix clipped (peak at or above 0 dBFS). Lower the master or track levels and export again.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissExport) { Text("OK") } }
        )
        is ExportState.Failed -> AlertDialog(
            onDismissRequest = vm::dismissExport,
            title = { Text("Export failed") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = vm::dismissExport) { Text("OK") } }
        )
    }
}
