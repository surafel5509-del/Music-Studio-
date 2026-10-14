package com.wavestudio.app.ui.editor

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wavestudio.app.engine.TransportState
import com.wavestudio.app.engine.AudioEngineController
import com.wavestudio.app.ui.common.*
import com.wavestudio.app.ui.components.SpectrumAnalyzerView
import com.wavestudio.app.ui.mixer.MixerPanel
import com.wavestudio.app.ui.timeline.TimelineView
import kotlinx.coroutines.flow.combine
import kotlin.math.max

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
    val project by vm.project.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val clips by vm.clips.collectAsState()
    val selectedClip by vm.selectedClip.collectAsState()
    val snap by vm.snapEnabled.collectAsState()
    val metronome by vm.metronome.collectAsState()
    val bpm by vm.bpm.collectAsState()
    val loopOn by vm.loop.collectAsState()
    val transport by vm.transportState.collectAsState()
    val playhead by vm.playheadFrames.collectAsState()
    val peak by vm.masterPeak.collectAsState()
    val rms by vm.masterRms.collectAsState()
    val inp by vm.inputLevel.collectAsState()
    val spectrum by vm.spectrum.collectAsState()
    val lufs by vm.lufs.collectAsState()
    val sr by vm.engine.sampleRate.collectAsState()

    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTablet = config.screenWidthDp >= 600

    LaunchedEffect(projectId) { vm.loadProject(projectId) }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importAudio(it) }
    }

    var showMixer by remember { mutableStateOf(false) }
    var showEffectsForTrack by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(project.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                        Text("${(sr/1000).toInt()}kHz · ${project.bitDepth}-bit · ${tracks.size} tracks",
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = { vm.save(); onBack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { vm.undo() }) { Icon(Icons.Default.Undo, "Undo") }
                    IconButton(onClick = { vm.redo() }) { Icon(Icons.Default.Redo, "Redo") }
                    IconButton(onClick = { pickAudio.launch(arrayOf("audio/*")) }) { Icon(Icons.Default.FolderOpen, "Import") }
                    IconButton(onClick = { vm.save() }) { Icon(Icons.Default.Save, "Save") }
                    IconButton(onClick = onOpenPlugins) { Icon(Icons.Default.Extension, "Plugins") }
                }
            )
        },
        bottomBar = {
            TransportBar(
                transport = transport,
                playhead = playhead,
                sampleRate = sr,
                bpm = bpm,
                metronome = metronome,
                snap = snap,
                loop = loopOn,
                peak = peak, rms = rms, inputLevel = inp,
                onPlay = { vm.play() }, onPause = { vm.pause() }, onStop = { vm.stop() },
                onRecord = { vm.record() },
                onSeek = { vm.seek(it) },
                onToggleMetronome = { vm.setMetronome(!metronome) },
                onSetBpm = { vm.setBpm(it) },
                onToggleSnap = { vm.setSnap(!snap) },
                onToggleLoop = { vm.toggleLoop() },
                onJump = { vm.seek(max(0, playhead + (it*sr).toLong())) }
            )
        }
    ) { pad ->
        if (isLandscape || isTablet) {
            Row(modifier = Modifier.padding(pad).fillMaxSize()) {
                // Left: track controls
                TrackControlsPanel(
                    tracks = tracks,
                    onVolume = { id, v -> vm.setTrackVolume(id, v) },
                    onPan = { id, p -> vm.setTrackPan(id, p) },
                    onMute = { id -> vm.setTrackMute(id, tracks.first { it.id == id }.mute.not()) },
                    onSolo = { id -> vm.setTrackSolo(id, tracks.first { it.id == id }.solo.not()) },
                    onArm = { id -> vm.setTrackArm(id, tracks.first { it.id == id }.arm.not()) },
                    onAddTrack = { vm.addTrack("Track ${tracks.size+1}") },
                    onOpenEffects = { showEffectsForTrack = it },
                    modifier = Modifier.width(180.dp).fillMaxHeight()
                )
                // Center: timeline
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Tool strip
                    ToolStrip(
                        snap = snap, metronome = metronome, loop = loopOn, bpm = bpm,
                        onSnap = { vm.setSnap(!snap) },
                        onMetronome = { vm.setMetronome(!metronome) },
                        onLoop = { vm.toggleLoop() },
                        onBpm = { vm.setBpm(it) },
                        onMixer = { showMixer = !showMixer },
                        onZoomIn = {}, onZoomOut = {}
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        TimelineView(
                            tracks = tracks,
                            clips = clips,
                            playheadFrames = playhead,
                            totalFrames = max(playhead + (30L*60*sr.toLong()), clips.maxOfOrNull { it.startFrame+it.lengthFrames } ?: (sr*60).toLong()),
                            sampleRate = sr,
                            bpm = bpm,
                            snapToGrid = snap,
                            selectedClipId = selectedClip,
                            onSeek = { vm.seek(it) },
                            onClipMove = { id, s, t -> vm.moveClip(id, s, t) },
                            onClipSelected = { vm.selectClip(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // Bottom: analyzer + mini meter
                    Row(modifier = Modifier.height(120.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                        SpectrumAnalyzerView(bins = spectrum, modifier = Modifier.weight(1f).fillMaxHeight())
                        Column(modifier = Modifier.width(120.dp).fillMaxHeight().padding(4.dp)) {
                            Text("LUFS", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("%.1f".format(lufs), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("Integrated", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                // Right: mixer (collapsible)
                AnimatedVisibility(visible = showMixer, modifier = Modifier.width(260.dp).fillMaxHeight()) {
                    MixerPanel(
                        tracks = tracks, peak = peak, rms = rms,
                        onVolume = { id, v -> vm.setTrackVolume(id, v) },
                        onPan = { id, p -> vm.setTrackPan(id, p) },
                        onMute = { id -> vm.setTrackMute(id, true) },
                        onSolo = { id -> vm.setTrackSolo(id, true) }
                    )
                }
            }
        } else {
            // Portrait mode
            Column(modifier = Modifier.padding(pad).fillMaxSize()) {
                ToolStrip(
                    snap = snap, metronome = metronome, loop = loopOn, bpm = bpm,
                    onSnap = { vm.setSnap(!snap) },
                    onMetronome = { vm.setMetronome(!metronome) },
                    onLoop = { vm.toggleLoop() },
                    onBpm = { vm.setBpm(it) },
                    onMixer = { showMixer = !showMixer },
                    onZoomIn = {}, onZoomOut = {}
                )
                Box(modifier = Modifier.weight(1f)) {
                    TimelineView(
                        tracks = tracks, clips = clips, playheadFrames = playhead,
                        totalFrames = max(playhead + (sr*60).toLong(), clips.maxOfOrNull { it.startFrame+it.lengthFrames } ?: (sr*60).toLong()),
                        sampleRate = sr, bpm = bpm, snapToGrid = snap, selectedClipId = selectedClip,
                        onSeek = { vm.seek(it) }, onClipMove = { id,s,t -> vm.moveClip(id,s,t) },
                        onClipSelected = { vm.selectClip(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // Compact track strip
                TrackStrip(
                    tracks = tracks,
                    onMute = { vm.setTrackMute(it, tracks.first { t -> t.id == it }.mute.not()) },
                    onSolo = { vm.setTrackSolo(it, tracks.first { t -> t.id == it }.solo.not()) },
                    onArm = { vm.setTrackArm(it, tracks.first { t -> t.id == it }.arm.not()) },
                    onAdd = { vm.addTrack("Track ${tracks.size+1}") }
                )
                if (showMixer) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth().height(200.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        MixerPanel(
                            tracks = tracks, peak = peak, rms = rms,
                            onVolume = { id,v -> vm.setTrackVolume(id,v) },
                            onPan = { id,p -> vm.setTrackPan(id,p) },
                            onMute = {}, onSolo = {},
                            horizontal = true
                        )
                    }
                }
            }
        }
    }

    showEffectsForTrack?.let { tid ->
        onOpenEffects(tid)
        showEffectsForTrack = null
    }
}

@Composable
fun ToolStrip(
    snap: Boolean, metronome: Boolean, loop: Boolean, bpm: Float,
    onSnap: () -> Unit, onMetronome: () -> Unit, onLoop: () -> Unit,
    onBpm: (Float) -> Unit, onMixer: () -> Unit, onZoomIn: () -> Unit, onZoomOut: () -> Unit
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ToolbarButton(Icons.Default.GridOn, "Snap", onClick = onSnap, active = snap)
            ToolbarButton(Icons.Default.Timer, "Click", onClick = onMetronome, active = metronome)
            ToolbarButton(Icons.Default.Repeat, "Loop", onClick = onLoop, active = loop)
            ToolbarButton(Icons.Default.Tune, "Mixer", onClick = onMixer)
            Spacer(Modifier.weight(1f))
            Text("BPM", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BpmStepper(bpm = bpm, onValue = onBpm)
            ToolbarButton(Icons.Default.Remove, "-", onClick = onZoomOut)
            ToolbarButton(Icons.Default.Add, "+", onClick = onZoomIn)
        }
    }
}

@Composable
fun BpmStepper(bpm: Float, onValue: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onValue((bpm - 1f).coerceAtLeast(20f)) }, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Remove, null, modifier = Modifier.size(14.dp))
        }
        Text("${bpm.toInt()}", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
        IconButton(onClick = { onValue(bpm + 1f) }, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
fun TransportBar(
    transport: TransportState, playhead: Long, sampleRate: Float,
    bpm: Float, metronome: Boolean, snap: Boolean, loop: Boolean,
    peak: Float, rms: Float, inputLevel: Float,
    onPlay: () -> Unit, onPause: () -> Unit, onStop: () -> Unit, onRecord: () -> Unit,
    onSeek: (Long) -> Unit, onToggleMetronome: () -> Unit, onSetBpm: (Float) -> Unit,
    onToggleSnap: () -> Unit, onToggleLoop: () -> Unit, onJump: (Float) -> Unit
) {
    val colors = WaveStudioTheme.colors
    Surface(tonalElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Record button
            IconButton(
                onClick = onRecord,
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .background(if (transport == TransportState.RECORDING) Color.Red else MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(Icons.Default.FiberManualRecord, "Record",
                    tint = if (transport == TransportState.RECORDING) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TransportButton(Icons.Default.Stop, active = false, onClick = onStop, contentDescription = "Stop")
            if (transport == TransportState.PLAYING || transport == TransportState.RECORDING) {
                TransportButton(Icons.Default.Pause, active = true, onClick = onPause, contentDescription = "Pause")
            } else {
                TransportButton(Icons.Default.PlayArrow, active = false, onClick = onPlay, contentDescription = "Play")
            }
            TransportButton(Icons.Default.SkipPrevious, onClick = { onSeek(0L) }, contentDescription = "RTZ")

            Spacer(Modifier.width(8.dp))
            // Time display
            Surface(color = Color.Black, shape = RoundedCornerShape(4.dp), modifier = Modifier) {
                val totalSec = playhead / sampleRate.coerceAtLeast(1f)
                val m = (totalSec / 60).toInt()
                val s = (totalSec % 60).toInt()
                val ms = ((totalSec - (m*60+s))*100).toInt()
                Text(
                    "%02d:%02d.%02d".format(m, s, ms),
                    color = Color(0xFF43E97B),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.width(8.dp))
            // Meters
            VuMeter(peak = peak, rms = rms, modifier = Modifier.size(20.dp, 36.dp))
            VuMeter(peak = inputLevel, rms = inputLevel*0.7f, modifier = Modifier.size(20.dp, 36.dp), label = "IN")

            Spacer(Modifier.weight(1f))
            // Loop / Click buttons
            ToolbarButton(Icons.Default.Repeat, "Loop", active = loop, onClick = onToggleLoop)
            ToolbarButton(Icons.Default.Timer, "Click", active = metronome, onClick = onToggleMetronome)
        }
    }
}

@Composable
fun TrackControlsPanel(
    tracks: List<com.wavestudio.app.domain.Track>,
    onVolume: (Int, Float) -> Unit,
    onPan: (Int, Float) -> Unit,
    onMute: (Int) -> Unit,
    onSolo: (Int) -> Unit,
    onArm: (Int) -> Unit,
    onAddTrack: () -> Unit,
    onOpenEffects: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(tonalElevation = 2.dp, modifier = modifier) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                Text("Tracks", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onAddTrack, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Add, "Add", modifier = Modifier.size(16.dp)) }
            }
            val state = rememberLazyListState()
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items(tracks) { t ->
                    TrackControlRow(
                        track = t,
                        onVolume = { onVolume(t.id, it) },
                        onPan = { onPan(t.id, it) },
                        onMute = { onMute(t.id) },
                        onSolo = { onSolo(t.id) },
                        onArm = { onArm(t.id) },
                        onEffects = { onOpenEffects(t.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun TrackControlRow(
    track: com.wavestudio.app.domain.Track,
    onVolume: (Float) -> Unit,
    onPan: (Float) -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    onArm: () -> Unit,
    onEffects: () -> Unit
) {
    val trackColor = Color(track.color.toLong())
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().padding(2.dp)) {
        Row(
            modifier = Modifier.padding(4.dp).height(80.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier.width(4.dp).fillMaxHeight().background(trackColor, RoundedCornerShape(2.dp))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(track.name, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Row {
                    IconToggleButton(checked = track.mute, onCheckedChange = { onMute() }, modifier = Modifier.size(28.dp)) {
                        Text("M", fontSize = 9.sp, color = if (track.mute) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconToggleButton(checked = track.solo, onCheckedChange = { onSolo() }, modifier = Modifier.size(28.dp)) {
                        Text("S", fontSize = 9.sp, color = if (track.solo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onArm, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Mic, null, modifier = Modifier.size(14.dp),
                            tint = if (track.arm) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onEffects, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Tune, null, modifier = Modifier.size(14.dp))
                    }
                }
                // Pan slider
                Slider(
                    value = track.pan, onValueChange = onPan,
                    valueRange = -1f..1f, modifier = Modifier.height(16.dp).fillMaxWidth()
                )
                // Volume slider
                Slider(
                    value = track.volume, onValueChange = onVolume,
                    valueRange = 0f..2f, modifier = Modifier.height(16.dp).fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun TrackStrip(
    tracks: List<com.wavestudio.app.domain.Track>,
    onMute: (Int) -> Unit, onSolo: (Int) -> Unit, onArm: (Int) -> Unit, onAdd: () -> Unit
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add") }
            androidx.compose.foundation.lazy.LazyRow(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                items(tracks) { t ->
                    val c = Color(t.color.toLong())
                    Surface(
                        color = c.copy(alpha = 0.3f),
                        modifier = Modifier.padding(2.dp).size(52.dp).border(1.dp, c, RoundedCornerShape(6.dp))
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(2.dp)) {
                            Text(t.name.take(4), fontSize = 9.sp, maxLines = 1)
                            Row {
                                IconToggleButton(t.mute, onCheckedChange = { onMute(t.id) }, modifier = Modifier.size(20.dp)) {
                                    Text("M", fontSize = 7.sp)
                                }
                                IconToggleButton(t.solo, onCheckedChange = { onSolo(t.id) }, modifier = Modifier.size(20.dp)) {
                                    Text("S", fontSize = 7.sp)
                                }
                                IconToggleButton(t.arm, onCheckedChange = { onArm(t.id) }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Mic, null, modifier = Modifier.size(10.dp),
                                        tint = if (t.arm) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
