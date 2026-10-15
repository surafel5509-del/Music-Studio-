package com.wavestudio.app.ui.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavestudio.app.domain.Track
import com.wavestudio.app.ui.common.Knob
import com.wavestudio.app.ui.common.VuMeter
import kotlin.math.log10

@Composable
fun MixerPanel(
    tracks: List<Track>,
    peak: Float,
    rms: Float,
    onVolume: (Int, Float) -> Unit,
    onPan: (Int, Float) -> Unit,
    onMute: (Int) -> Unit,
    onSolo: (Int) -> Unit,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false
) {
    if (horizontal) {
        LazyRow(
            modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(4.dp)
        ) {
            items(tracks) { t ->
                CompactStrip(t, onVolume = { onVolume(t.id, it) }, onPan = { onPan(t.id, it) },
                    onMute = { onMute(t.id) }, onSolo = { onSolo(t.id) })
            }
            item { MasterStrip(peak, rms) }
        }
    } else {
        LazyRow(
            modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(6.dp)
        ) {
            items(tracks) { t ->
                ChannelStrip(track = t,
                    onVolume = { onVolume(t.id, it) }, onPan = { onPan(t.id, it) },
                    onMute = { onMute(t.id) }, onSolo = { onSolo(t.id) })
            }
            item { MasterStrip(peak, rms) }
        }
    }
}

@Composable
fun ChannelStrip(track: Track, onVolume: (Float)->Unit, onPan: (Float)->Unit, onMute: ()->Unit, onSolo: ()->Unit) {
    val color = Color(track.color.toLong())
    Surface(tonalElevation = 3.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.width(80.dp).fillMaxHeight()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
            Text(track.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Box(Modifier.width(3.dp).height(8.dp).background(color))
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                FilterChip(selected = track.mute, onClick = onMute, label = { Text("M", fontSize = 8.sp) })
                FilterChip(selected = track.solo, onClick = onSolo, label = { Text("S", fontSize = 8.sp) })
            }
            Knob(value = track.pan, range = -1f..1f, label = "PAN", size = 36.dp, onValueChange = onPan,
                formatter = { if (kotlin.math.abs(it) < 0.05f) "C" else if (it<0) "L${(it*-100).toInt()}" else "R${(it*100).toInt()}" })
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                VuMeter(peak = track.volume * 0.8f, rms = track.volume * 0.6f, modifier = Modifier.fillMaxHeight())
                VolumeFader(value = track.volume, onValue = onVolume)
            }
            val db = (20* log10(track.volume.coerceAtLeast(0.0001f)))
            Text("%.0fdB".format(db), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CompactStrip(track: Track, onVolume: (Float)->Unit, onPan: (Float)->Unit, onMute: ()->Unit, onSolo: ()->Unit) {
    val color = Color(track.color.toLong())
    Surface(tonalElevation = 2.dp, modifier = Modifier.width(64.dp).height(180.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
            Text(track.name.take(5), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Box(Modifier.width(3.dp).height(6.dp).background(color))
            Spacer(Modifier.height(2.dp))
            Row {
                TextButton(onClick = onMute, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("M", fontSize = 8.sp) }
                TextButton(onClick = onSolo, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("S", fontSize = 8.sp) }
            }
            VolumeFader(value = track.volume, onValue = onVolume, modifier = Modifier.weight(1f).width(40.dp))
        }
    }
}

@Composable
fun VolumeFader(value: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier) {
    // Simple vertical fader using slider
    Box(modifier = modifier) {
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValue,
            valueRange = 0f..2f,
            modifier = Modifier.fillMaxHeight().width(40.dp)
        )
    }
}

@Composable
fun MasterStrip(peak: Float, rms: Float) {
    Surface(tonalElevation = 4.dp, color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small,
        modifier = Modifier.width(80.dp).fillMaxHeight()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
            Text("MASTER", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                VuMeter(peak = peak, rms = rms, modifier = Modifier.fillMaxHeight())
            }
            Text("%.1f".format((20* log10(peak.coerceAtLeast(1e-9f)))) + "dB", fontSize = 9.sp)
        }
    }
}
