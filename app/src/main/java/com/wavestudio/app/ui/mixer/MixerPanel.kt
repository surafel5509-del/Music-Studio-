package com.wavestudio.app.ui.mixer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavestudio.app.engine.MasterMeters
import com.wavestudio.app.project.BusDoc
import com.wavestudio.app.project.TrackDoc
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.wavestudio.app.ui.common.Knob
import com.wavestudio.app.ui.common.VuMeter
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

private const val FADER_MIN_DB = -60f
private const val FADER_MAX_DB = 6f

private fun gainToPos(g: Float): Float =
    if (g.isNaN() || g <= 0.001f) 0f else ((20f * log10(g) - FADER_MIN_DB) / (FADER_MAX_DB - FADER_MIN_DB)).coerceIn(0f, 1f)
private fun posToGain(p: Float): Float =
    if (p <= 0.004f) 0f else 10f.pow((FADER_MIN_DB + p * (FADER_MAX_DB - FADER_MIN_DB)) / 20f)

fun gainLabel(g: Float): String = if (g.isNaN() || g <= 0.001f) "-inf" else "%.1f".format(20f * log10(g))

/** Mixer: one strip per track + master. Meter levels are the real per-track and master peaks from the engine. */
@Composable
fun MixerPanel(
    tracks: List<TrackDoc>,
    trackMeters: Map<Int, Pair<Float, Float>>,
    master: MasterMeters,
    masterVolume: Float,
    onVolume: (Int, Float) -> Unit,
    onPan: (Int, Float) -> Unit,
    onMute: (Int) -> Unit,
    onSolo: (Int) -> Unit,
    onMasterVolume: (Float) -> Unit,
    onEffects: (Int) -> Unit,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
    buses: List<BusDoc> = emptyList(),
    onSend: (trackId: Int, bus: Int, level: Float) -> Unit = { _, _, _ -> },
    onBusLevel: (Int, Float) -> Unit = { _, _ -> },
    onAddBus: () -> Unit = {},
    onRemoveBus: (Int) -> Unit = {}
) {
    var sendsFor by remember { mutableStateOf<Int?>(null) }
    val sendTrack = tracks.firstOrNull { it.id == sendsFor }
    if (sendTrack != null) {
        AlertDialog(
            onDismissRequest = { sendsFor = null },
            title = { Text("Sends — ${sendTrack.name}") },
            text = {
                Column {
                    if (buses.isEmpty()) Text("No aux bus yet. Add one with the + AUX strip at the end of the mixer, put effects such as reverb on it, then send tracks to it here.", fontSize = 13.sp)
                    buses.forEach { b ->
                        val lv = sendTrack.sends[b.index] ?: 0f
                        Text("${b.name}: " + (if (lv <= 0f) "off" else "${gainLabel(lv)} dB") + "  (post-fader)", fontSize = 12.sp)
                        Slider(value = lv, onValueChange = { onSend(sendTrack.id, b.index, if (it < 0.02f) 0f else it) }, valueRange = 0f..2f)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sendsFor = null }) { Text("Done") } }
        )
    }
    LazyRow(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        verticalAlignment = if (horizontal) Alignment.Bottom else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (horizontal) 4.dp else 6.dp),
        contentPadding = PaddingValues(if (horizontal) 4.dp else 6.dp)
    ) {
        items(tracks, key = { it.id }) { t ->
            val m = trackMeters[t.id]
            ChannelStrip(
                track = t, peak = if (m == null) 0f else maxOf(m.first, m.second),
                onVolume = { onVolume(t.id, it) }, onPan = { onPan(t.id, it) },
                onMute = { onMute(t.id) }, onSolo = { onSolo(t.id) }, onEffects = { onEffects(t.id) },
                sendCount = t.sends.size, onSends = { sendsFor = t.id }
            )
        }
        items(buses, key = { "bus${it.index}" }) { b ->
            BusStrip(b, onLevel = { onBusLevel(b.index, it) }, onEffects = { onEffects(-(b.index + 1)) }, onRemove = { onRemoveBus(b.index) })
        }
        if (buses.size < com.wavestudio.app.project.MAX_BUSES) item {
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.width(64.dp).fillMaxHeight()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TextButton(onClick = onAddBus) { Text("+ AUX", fontSize = 11.sp) } }
            }
        }
        item { MasterStrip(master, masterVolume, onMasterVolume, onEffects = { onEffects(0) }) }
    }
}

@Composable
fun ChannelStrip(track: TrackDoc, peak: Float, onVolume: (Float) -> Unit, onPan: (Float) -> Unit,
                 onMute: () -> Unit, onSolo: () -> Unit, onEffects: () -> Unit,
                 sendCount: Int = 0, onSends: () -> Unit = {}) {
    val color = Color(track.color)
    Surface(tonalElevation = 3.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.width(84.dp).fillMaxHeight()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
            Text(track.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Box(Modifier.width(24.dp).height(3.dp).background(color))
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                MiniToggle("M", track.mute, MaterialTheme.colorScheme.error, onMute)
                MiniToggle("S", track.solo, MaterialTheme.colorScheme.tertiary, onSolo)
            }
            Spacer(Modifier.height(2.dp))
            TextButton(onClick = onEffects, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                Text(if (track.effects.isEmpty()) "FX" else "FX (${track.effects.size})", fontSize = 10.sp)
            }
            TextButton(onClick = onSends, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                Text(if (sendCount == 0) "SEND" else "SEND ($sendCount)", fontSize = 10.sp)
            }
            Knob(value = track.pan, range = -1f..1f, label = "PAN", size = 32.dp, onValueChange = onPan, defaultValue = 0f,
                formatter = { if (abs(it) < 0.05f) "C" else if (it < 0) "L${(-it * 100).toInt()}" else "R${(it * 100).toInt()}" })
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                VuMeter(peak = peak, rms = peak, modifier = Modifier.fillMaxHeight())
                VerticalFader(track.volume, onVolume, Modifier.fillMaxHeight().width(30.dp))
            }
            Text("${gainLabel(track.volume)} dB", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Compact mute/solo style toggle (22 dp: a Material chip is 32 dp tall and squeezed the fader out of short strips). */
@Composable
fun MiniToggle(text: String, on: Boolean, onColor: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = MaterialTheme.shapes.extraSmall,
        color = if (on) onColor else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(26.dp, 22.dp)
    ) { Box(contentAlignment = Alignment.Center) { Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
}

/** Aux bus return: the summed (post-fader) sends go through the bus effect chain and come back at this level. */
@Composable
fun BusStrip(bus: BusDoc, onLevel: (Float) -> Unit, onEffects: () -> Unit, onRemove: () -> Unit) {
    Surface(tonalElevation = 4.dp, color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small,
        modifier = Modifier.width(84.dp).fillMaxHeight()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
            Text(bus.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("AUX ${bus.index + 1}", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onEffects, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                Text(if (bus.effects.isEmpty()) "FX" else "FX (${bus.effects.size})", fontSize = 10.sp)
            }
            Knob(value = bus.level, range = 0f..2f, label = "RETURN", size = 36.dp, onValueChange = onLevel, defaultValue = 1f,
                formatter = { gainLabel(it) + " dB" })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRemove, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                Text("Remove", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun MasterStrip(meters: MasterMeters, volume: Float, onVolume: (Float) -> Unit, onEffects: () -> Unit) {
    Surface(tonalElevation = 4.dp, color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small,
        modifier = Modifier.width(100.dp).fillMaxHeight()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
            Text("MASTER", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onEffects, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) { Text("FX", fontSize = 10.sp) }
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                VuMeter(peak = meters.peakL, rms = meters.rmsL, modifier = Modifier.fillMaxHeight())
                VuMeter(peak = meters.peakR, rms = meters.rmsR, modifier = Modifier.fillMaxHeight())
                VerticalFader(volume, onVolume, Modifier.fillMaxHeight().width(30.dp))
            }
            val pk = maxOf(meters.peakL, meters.peakR)
            Text("pk " + (if (pk < 1e-5f) "-inf" else "%.1f".format(20f * log10(pk))) + " dB", fontSize = 9.sp)
            if (meters.clipCount > 0)
                Text("CLIP ×${meters.clipCount}", fontSize = 9.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
    }
}

/** Vertical fader with a dB taper (-60 … +6 dB). Double-tap resets to 0 dB. Safe for zero-size layouts and NaN. */
@Composable
fun VerticalFader(value: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val unity = MaterialTheme.colorScheme.onSurfaceVariant
    val onValueS = rememberUpdatedState(onValue)
    val pos = gainToPos(if (value.isNaN()) 0f else value)
    fun emit(y: Float, h: Int) {
        if (h <= 0 || y.isNaN()) return
        onValueS.value(posToGain((1f - y / h).coerceIn(0f, 1f)))
    }
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ -> emit(change.position.y, size.height); change.consume() }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onValueS.value(1f) },
                    onTap = { o -> emit(o.y, size.height) }
                )
            }
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val cx = size.width / 2
        drawRoundRect(track, topLeft = Offset(cx - 3.dp.toPx(), 0f), size = Size(6.dp.toPx(), size.height), cornerRadius = CornerRadius(3.dp.toPx()))
        val zeroY = size.height * (1f - gainToPos(1f))
        drawLine(unity, Offset(cx - 9.dp.toPx(), zeroY), Offset(cx + 9.dp.toPx(), zeroY), strokeWidth = 1.dp.toPx())
        val y = size.height * (1f - pos)
        drawRoundRect(fill, topLeft = Offset(cx - 3.dp.toPx(), y), size = Size(6.dp.toPx(), (size.height - y).coerceAtLeast(0f)), cornerRadius = CornerRadius(3.dp.toPx()))
        drawRoundRect(Color.White, topLeft = Offset(cx - 12.dp.toPx(), y - 6.dp.toPx()), size = Size(24.dp.toPx(), 12.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx()))
        drawLine(Color.DarkGray, Offset(cx - 12.dp.toPx(), y), Offset(cx + 12.dp.toPx(), y), strokeWidth = 1.5f)
    }
}
