package com.wavestudio.app.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wavestudio.app.project.DrumVoice
import com.wavestudio.app.project.Instruments
import com.wavestudio.app.project.MAX_BAR_COUNT
import com.wavestudio.app.project.ROLL_HIGH
import com.wavestudio.app.project.ROLL_LOW
import com.wavestudio.app.project.STEPS_PER_BAR
import com.wavestudio.app.project.StudioOps

private val VOICE_COLORS = mapOf(
    DrumVoice.KICK to Color(0xFFFF6B6B), DrumVoice.SNARE to Color(0xFFFFB84A), DrumVoice.CLAP to Color(0xFFFF8FD0),
    DrumVoice.HAT to Color(0xFF5DD6A2), DrumVoice.OPEN_HAT to Color(0xFF4AC8E2), DrumVoice.TOM to Color(0xFF8C9EFF),
    DrumVoice.RIM to Color(0xFFC4A3FF), DrumVoice.COWBELL to Color(0xFFE2D04A)
)

/**
 * Beat maker (16-step lanes, 1–8 bars, swing) and piano roll (synthesised instruments). Both render offline to a normal
 * audio clip that is added to the project; the buttons next to "Add" only audition through the phone's output.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(onBack: () -> Unit, vm: StudioViewModel = hiltViewModel()) {
    val studio by vm.studio.collectAsState()
    val bpm by vm.bpm.collectAsState()
    val playing by vm.playing.collectAsState()
    val message by vm.message.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Studio") }, navigationIcon = {
                IconButton(onClick = { vm.stopPreview(); onBack() }) { Icon(Icons.Default.ArrowBack, "Back") }
            })
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Beat maker") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Piano roll") })
            }
            Text("Tempo ${"%.1f".format(bpm)} bpm (project tempo). Patterns are saved with the project and become normal audio clips when you add them.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            if (tab == 0) BeatTab(vm, studio.beat, playing) else RollTab(vm, studio.roll, playing)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BeatTab(vm: StudioViewModel, beat: com.wavestudio.app.project.BeatDoc, playing: Boolean) {
    val hScroll = rememberScrollState()
    val offBg = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text("Bars", fontSize = 12.sp)
            listOf(1, 2, 4, MAX_BAR_COUNT).forEach { b -> FilterChip(selected = beat.bars == b, onClick = { vm.setBeatBars(b) }, label = { Text("$b") }) }
            PlayStopButton(playing, onPlay = vm::previewBeat, onStop = vm::stopPreview)
            OutlinedButton(onClick = vm::demoBeat) { Text("Demo") }
            OutlinedButton(onClick = vm::clearBeat) { Text("Clear") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Swing ${(beat.swing * 100).toInt()}%", fontSize = 12.sp, modifier = Modifier.width(84.dp))
            Slider(value = beat.swing, onValueChange = vm::setSwing, valueRange = 0f..0.6f, modifier = Modifier.weight(1f))
        }
        Row {
            Column(Modifier.width(92.dp)) {
                for (v in DrumVoice.values()) {
                    val row = beat.rows.firstOrNull { it.voice == v.id }
                    val muted = row?.mute == true
                    Box(
                        Modifier.padding(1.dp).height(30.dp).width(90.dp).clip(RoundedCornerShape(4.dp))
                            .background((VOICE_COLORS[v] ?: Color.Gray).copy(alpha = if (muted) 0.15f else 0.45f))
                            .clickable { vm.setMute(v, !muted) },
                        contentAlignment = Alignment.CenterStart
                    ) { Text((if (muted) "✕ " else "") + v.label, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp)) }
                }
            }
            Column(Modifier.horizontalScroll(hScroll)) {
                for (v in DrumVoice.values()) {
                    val row = beat.rows.firstOrNull { it.voice == v.id }
                    val color = VOICE_COLORS[v] ?: Color.Gray
                    Row {
                        for (step in 0 until beat.totalSteps) {
                            val level = row?.at(step) ?: 0
                            val groupShade = (step / 4) % 2 == 0
                            Box(
                                Modifier.padding(1.dp).size(32.dp, 30.dp).clip(RoundedCornerShape(4.dp))
                                    .background(when (level) {
                                        0 -> if (groupShade) offBg else offBg.copy(alpha = 0.55f)
                                        1 -> color.copy(alpha = 0.35f)
                                        2 -> color.copy(alpha = 0.7f)
                                        else -> color
                                    })
                                    .clickable { vm.cycleStep(v, step) }
                            )
                        }
                    }
                }
            }
        }
        Text("Tap a step: off → normal → accent → ghost → off. Tap a lane name to mute it. Drag the grid sideways for more bars.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = vm::addBeatToProject, modifier = Modifier.fillMaxWidth()) { Text("Add beat to project (new track at playhead)") }
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RollTab(vm: StudioViewModel, roll: com.wavestudio.app.project.RollDoc, playing: Boolean) {
    var lenSteps by rememberSaveable { mutableStateOf(4) }
    val noteColor = MaterialTheme.colorScheme.primary
    val rowLight = MaterialTheme.colorScheme.surface
    val rowDark = MaterialTheme.colorScheme.surfaceVariant
    val gridLine = MaterialTheme.colorScheme.outline
    val gridStrong = MaterialTheme.colorScheme.onSurfaceVariant
    val rollState = rememberUpdatedState(roll)
    val lenState = rememberUpdatedState(lenSteps)
    val cellW = 30.dp
    val rowH = 22.dp
    val cw = with(LocalDensity.current) { cellW.toPx() }
    val rh = with(LocalDensity.current) { rowH.toPx() }
    val rows = ROLL_HIGH - ROLL_LOW + 1
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    LaunchedEffect(Unit) { vScroll.scrollTo((rh * (ROLL_HIGH - 84)).toInt()) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Instruments.all.forEach { i -> FilterChip(selected = roll.instrument == i.id, onClick = { vm.setInstrument(i.id) }, label = { Text(i.label) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Text("Note", fontSize = 12.sp)
                listOf(1 to "1/16", 2 to "1/8", 4 to "1/4", 8 to "1/2", 16 to "1 bar").forEach { (n, label) ->
                    FilterChip(selected = lenSteps == n, onClick = { lenSteps = n }, label = { Text(label) })
                }
                Text("Bars", fontSize = 12.sp)
                listOf(1, 2, 4, MAX_BAR_COUNT).forEach { b -> FilterChip(selected = roll.bars == b, onClick = { vm.setRollBars(b) }, label = { Text("$b") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                PlayStopButton(playing, onPlay = vm::previewRoll, onStop = vm::stopPreview)
                OutlinedButton(onClick = { vm.transpose(-12) }) { Text("−8ve") }
                OutlinedButton(onClick = { vm.transpose(12) }) { Text("+8ve") }
                OutlinedButton(onClick = vm::demoRoll) { Text("Demo") }
                OutlinedButton(onClick = vm::clearRoll) { Text("Clear") }
                Button(onClick = vm::addRollToProject) { Text("Add to project") }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(vScroll)) {
            Column(Modifier.width(52.dp)) {
                for (p in ROLL_HIGH downTo ROLL_LOW) {
                    val black = StudioOps.isBlackKey(p)
                    Box(
                        Modifier.height(rowH).fillMaxWidth().background(if (black) Color(0xFF222222) else Color(0xFFEDEDED)).clickable { vm.auditionNote(p) },
                        contentAlignment = Alignment.CenterStart
                    ) { Text(StudioOps.pitchName(p), fontSize = 9.sp, color = if (black) Color.White else Color.Black, modifier = Modifier.padding(start = 4.dp)) }
                }
            }
            Box(Modifier.horizontalScroll(hScroll)) {
                Canvas(
                    Modifier.size(cellW * roll.totalSteps, rowH * rows).pointerInput(cw, rh) {
                        detectTapGestures { off ->
                            val r = rollState.value
                            val step = (off.x / cw).toInt()
                            val pitch = ROLL_HIGH - (off.y / rh).toInt()
                            if (step < 0 || step >= r.totalSteps || pitch < ROLL_LOW || pitch > ROLL_HIGH) return@detectTapGestures
                            if (StudioOps.noteAt(r, pitch, step) != null) vm.removeNote(pitch, step)
                            else { vm.addNote(pitch, step, lenState.value); vm.auditionNote(pitch) }
                        }
                    }
                ) {
                    for (i in 0 until rows) {
                        val p = ROLL_HIGH - i
                        drawRect(if (StudioOps.isBlackKey(p)) rowDark else rowLight, Offset(0f, i * rh), Size(size.width, rh))
                        drawLine(gridLine.copy(alpha = 0.4f), Offset(0f, i * rh), Offset(size.width, i * rh), 1f)
                    }
                    for (s in 0..roll.totalSteps) {
                        val strong = s % STEPS_PER_BAR == 0
                        val beat = s % 4 == 0
                        drawLine(if (strong) gridStrong else gridLine.copy(alpha = if (beat) 0.9f else 0.35f), Offset(s * cw, 0f), Offset(s * cw, size.height), if (strong) 2f else 1f)
                    }
                    for (n in roll.notes) {
                        drawRoundRect(noteColor.copy(alpha = 0.55f + 0.45f * n.velocity), Offset(n.step * cw + 1f, (ROLL_HIGH - n.pitch) * rh + 1f),
                            Size(n.length * cw - 2f, rh - 2f), CornerRadius(4f, 4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayStopButton(playing: Boolean, onPlay: () -> Unit, onStop: () -> Unit) {
    if (playing) OutlinedButton(onClick = onStop) { Icon(Icons.Default.Stop, "Stop"); Text(" Stop") }
    else OutlinedButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, "Play"); Text(" Play") }
}
