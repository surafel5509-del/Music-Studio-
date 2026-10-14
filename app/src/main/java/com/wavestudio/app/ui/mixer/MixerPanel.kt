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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.wavestudio.app.project.TrackDoc
import com.wavestudio.app.ui.common.Knob
import com.wavestudio.app.ui.common.VuMeter
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

private const val FADER_MIN_DB = -60f
private const val FADER_MAX_DB = 6f

private fun gainToPos(g: Float): Float =
    if (g <= 0.001f) 0f else ((20f * log10(g) - FADER_MIN_DB) / (FADER_MAX_DB - FADER_MIN_DB)).coerceIn(0f, 1f)
private fun posToGain(p: Float): Float =
    if (p <= 0.004f) 0f else 10f.pow((FADER_MIN_DB + p * (FADER_MAX_DB - FADER_MIN_DB)) / 20f)

fun gainLabel(g: Float): String = if (g <= 0.001f) "-inf" else "%.1f".format(20f * log10(g))

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
    horizontal: Boolean = false
) {
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
                onMute = { onMute(t.id) }, onSolo = { onSolo(t.id) }, onEffects = { onEffects(t.id) }
            )
        }
        item { MasterStrip(master, masterVolume, onMasterVolume, onEffects = { onEffects(0) }) }
    }
}

@Composable
fun ChannelStrip(track: TrackDoc, peak: Float, onVolume: (Float) -> Unit, onPan: (Float) -> Unit,
                 onMute: () -> Unit, onSolo: () -> Unit, onEffects: () -> Unit) {
    val color = Color(track.color)
    Surface(tonalElevation = 3.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.width(84.dp).fillMaxHeight()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
            Text(track.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Box(Modifier.width(24.dp).height(3.dp).background(color))
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                FilterChip(selected = track.mute, onClick = onMute, label = { Text("M", fontSize = 9.sp) })
                FilterChip(selected = track.solo, onClick = onSolo, label = { Text("S", fontSize = 9.sp) })
            }
            TextButton(onClick = onEffects, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                Text(if (track.effects.isEmpty()) "FX" else "FX (${track.effects.size})", fontSize = 10.sp)
            }
            Knob(value = track.pan, range = -1f..1f, label = "PAN", size = 36.dp, onValueChange = onPan,
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

/** Vertical fader with a dB taper (-60 … +6 dB). Double-tap resets to 0 dB. */
@Composable
fun VerticalFader(value: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val unity = MaterialTheme.colorScheme.onSurfaceVariant
    val pos = gainToPos(value)
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ ->
                    val p = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                    onValue(posToGain(p)); change.consume()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onValue(1f) },
                    onTap = { o -> onValue(posToGain((1f - o.y / size.height).coerceIn(0f, 1f))) }
                )
            }
    ) {
        val cx = size.width / 2
        drawRoundRect(track, topLeft = Offset(cx - 3.dp.toPx(), 0f), size = Size(6.dp.toPx(), size.height), cornerRadius = CornerRadius(3.dp.toPx()))
        val zeroY = size.height * (1f - gainToPos(1f))
        drawLine(unity, Offset(cx - 9.dp.toPx(), zeroY), Offset(cx + 9.dp.toPx(), zeroY), strokeWidth = 1.dp.toPx())
        val y = size.height * (1f - pos)
        drawRoundRect(fill, topLeft = Offset(cx - 3.dp.toPx(), y), size = Size(6.dp.toPx(), size.height - y), cornerRadius = CornerRadius(3.dp.toPx()))
        drawRoundRect(Color.White, topLeft = Offset(cx - 12.dp.toPx(), y - 6.dp.toPx()), size = Size(24.dp.toPx(), 12.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx()))
        drawLine(Color.DarkGray, Offset(cx - 12.dp.toPx(), y), Offset(cx + 12.dp.toPx(), y), strokeWidth = 1.5f)
    }
}
