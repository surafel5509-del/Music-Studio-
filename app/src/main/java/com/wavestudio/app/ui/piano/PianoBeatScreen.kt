package com.wavestudio.app.ui.piano

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wavestudio.app.engine.NativeAudioEngine
import com.wavestudio.app.ui.theme.WaveStudioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

/**
 * Full Piano Studio + Beat Maker combined view.
 * - Piano: 2 octaves (24 keys) with white/black rendering; touch plays a MIDI note
 *   (native synth is simple sine on the JNI for now – real synth is added in engine).
 * - Beat Maker: 16-step sequencer for 8 drum parts (kick, snare, closed-hat, open-hat,
 *   clap, tom, ride, perc). Press Play to sequence.
 */
@Composable
fun PianoBeatScreen(engine: NativeAudioEngine) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun tone(freq: Float, durSec: Float) {
        scope.launch(Dispatchers.IO) { playBeep(ctx, freq, durSec) }
    }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Header
        Text("Piano Studio & Beat Maker",
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)

        // Piano
        Text("Piano Roll (2 octaves)", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items(24) { i ->
                val isBlack = (i % 12) in listOf(1, 3, 6, 8, 10)
                val noteNum = 60 + i // Middle C = 60
                Box(
                    Modifier
                        .width(if (isBlack) 28.dp else 42.dp)
                        .height(200.dp)
                        .offset(x = if (isBlack) (-14).dp else 0.dp)
                        .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                        .background(if (isBlack) Color.Black else Color.White)
                        .clickable { playSynthNote(engine, noteNum) { f,d -> tone(f,d) } },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (!isBlack) {
                        Text(noteName(i), color = Color.Black, fontSize = MaterialTheme.typography.labelSmall.fontSize,
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
            }
        }

        // Beat Maker
        Text("Beat Maker (16 steps, 8 kits)", style = MaterialTheme.typography.titleMedium)
        val kits = listOf("KICK","SNARE","HAT-C","HAT-O","CLAP","TOM","RIDE","PERC")
        val grid = remember { List(kits.size) { mutableStateListOf(*BooleanArray(16) { false }.toTypedArray()) } }
        var playing by remember { mutableStateOf(false) }
        var bpm by remember { mutableFloatStateOf(120f) }
        var step by remember { mutableIntStateOf(0) }

        LaunchedEffect(playing, bpm) {
            while (playing) {
                step = (step + 1) % 16
                for (row in grid.indices) {
                    if (grid[row][step]) playDrum(engine, row) { f,d -> tone(f,d) }
                }
                delay((60000f / (bpm * 4)).toLong())
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { playing = !playing },
                colors = ButtonDefaults.buttonColors(containerColor = if (playing) WaveStudioTheme.colors.record else MaterialTheme.colorScheme.primary)) {
                Text(if (playing) "STOP" else "PLAY")
            }
            Spacer(Modifier.width(12.dp))
            Text("BPM ${bpm.toInt()}")
            Slider(bpm, { bpm = it }, valueRange = 60f..200f, Modifier.width(200.dp))
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { for (r in grid) for (i in r.indices) r[i] = false }) { Text("Clear") }
        }

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            kits.forEachIndexed { r, name ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(name, Modifier.width(54.dp), style = MaterialTheme.typography.bodySmall)
                    for (s in 0..15) {
                        val on = grid[r][s]
                        val beat = s % 4 == 0
                        val isCurrent = playing && s == step
                        Box(Modifier.size(26.dp).clip(RoundedCornerShape(4.dp)).background(when {
                            isCurrent -> WaveStudioTheme.colors.playhead
                            on -> MaterialTheme.colorScheme.primary
                            beat -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.surface
                        }).clickable { grid[r][s] = !grid[r][s] })
                    }
                }
            }
        }
    }
}

private fun noteName(i: Int): String {
    val names = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
    val oct = 4 + i / 12
    return names[i % 12].replace("#","") + oct
}

private var _tone: AudioTrack? = null

private fun playSynthNote(engine: NativeAudioEngine, midiNote: Int, tone: (Float,Float)->Unit) {
    val freq = 440.0 * 2.0.pow((midiNote - 69) / 12.0)
    tone(freq.toFloat(), 0.4f)
}
private fun playDrum(engine: NativeAudioEngine, kit: Int, tone: (Float,Float)->Unit) {
    val f = when (kit) { 0->60f;1->200f;2->8000f;3->6000f;4->1500f;5->100f;6->4000f;else->300f}
    val d = when (kit) { 0->0.25f;1->0.2f;2->0.05f;3->0.15f;4->0.12f;5->0.3f;6->0.18f;else->0.1f}
    tone(f, d)
}

@Synchronized private fun playBeep(ctx: Context, freq: Float, durSec: Float) {
    try {
        val sr = 44100; val n = (sr*durSec).toInt()
        val buf = ShortArray(n) { i ->
            val env = min(1f, min(i/400f, (n-i)/400f)).coerceAtLeast(0f)
            (sin(2.0*PI*freq*(i.toDouble()/sr))*0.4*env*32767.0).toInt().coerceIn(-32767,32767).toShort()
        }
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(n*2).setTransferMode(AudioTrack.MODE_STATIC).build()
        at.write(buf, 0, n)
        at.play()
        Thread.sleep((durSec*1000).toLong().coerceAtMost(500))
        at.stop(); at.release()
    } catch (_: Throwable) {}
}
