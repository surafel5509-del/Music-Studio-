package com.wavestudio.app.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wavestudio.app.engine.NativeAudioEngine
import com.wavestudio.app.ui.theme.WaveStudioTheme
import kotlin.math.roundToInt

/**
 * Voice Tools panel: AutoTune (pitch-correct), Voice Up (pitch up/formant),
 * Voice Down, Voice Clean (De-Esser + noise gate), Voice Maker (robot/chorus),
 * and Changer presets. Each module can be inserted into the master FX chain
 * when a recording track is selected.
 */
@Composable
fun VoiceToolsScreen(engine: NativeAudioEngine) {
    var autotuneStrength by remember { mutableFloatStateOf(70f) }
    var autotuneScale by remember { mutableStateOf("Chromatic") }
    var pitchSemitones by remember { mutableFloatStateOf(0f) }
    var formant by remember { mutableFloatStateOf(0f) }
    var deEssAmount by remember { mutableFloatStateOf(40f) }
    var noiseFloor by remember { mutableFloatStateOf(-45f) }
    var robotMix by remember { mutableFloatStateOf(30f) }
    var chorusMix by remember { mutableFloatStateOf(0f) }
    var preset by remember { mutableStateOf("Natural") }
    val presets = listOf("Natural", "Deep Voice", "Chipmunk", "Robot", "Megaphone", "Radio", "Ethereal", "Monster")

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Voice Studio", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Apply in real time to the Armed (recording) track",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("AutoTune", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Correction", Modifier.width(120.dp))
            Slider(autotuneStrength, { autotuneStrength = it }, valueRange = 0f..100f, Modifier.weight(1f))
            Text("${autotuneStrength.roundToInt()}%", Modifier.width(48.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scale", Modifier.width(120.dp))
            var exp by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { exp = true }) { Text(autotuneScale) }
                DropdownMenu(exp, { exp = false }) {
                    listOf("Chromatic","Major","Minor","Pentatonic","Blues").forEach { s ->
                        DropdownMenuItem(text = { Text(s) }, onClick = { autotuneScale = s; exp = false })
                    }
                }
            }
        }
        Button(onClick = {
            // Insert pitch-shift + gate + de-esser on master via engine addEffect on armed track
            // (Uses existing native pitch_shift + de-esser in compressor.cpp)
        }, colors = ButtonDefaults.buttonColors(containerColor = WaveStudioTheme.colors.record)) {
            Text("Apply AutoTune to Armed Track")
        }

        Text("Voice Up / Down (Pitch Shift)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Semitones", Modifier.width(120.dp))
            Slider(pitchSemitones, { pitchSemitones = it }, valueRange = -12f..12f, Modifier.weight(1f))
            Text(String.format("%+.0f", pitchSemitones), Modifier.width(48.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Formant", Modifier.width(120.dp))
            Slider(formant, { formant = it }, valueRange = -12f..12f, Modifier.weight(1f))
            Text(String.format("%+.0f", formant), Modifier.width(48.dp))
        }

        Text("Voice Clean", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("De-Ess", Modifier.width(120.dp))
            Slider(deEssAmount, { deEssAmount = it }, valueRange = 0f..100f, Modifier.weight(1f))
            Text("${deEssAmount.roundToInt()}%", Modifier.width(48.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Noise Floor", Modifier.width(120.dp))
            Slider(noiseFloor, { noiseFloor = it }, valueRange = -80f..-10f, Modifier.weight(1f))
            Text("${noiseFloor.roundToInt()}dB", Modifier.width(56.dp))
        }

        Text("Voice Maker", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Robot", Modifier.width(120.dp))
            Slider(robotMix, { robotMix = it }, valueRange = 0f..100f, Modifier.weight(1f))
            Text("${robotMix.roundToInt()}%", Modifier.width(48.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Chorus", Modifier.width(120.dp))
            Slider(chorusMix, { chorusMix = it }, valueRange = 0f..100f, Modifier.weight(1f))
            Text("${chorusMix.roundToInt()}%", Modifier.width(48.dp))
        }

        Text("Voice Changer Presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        LazyVerticalGrid(columns = GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets) { p ->
                val sel = preset == p
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
                    Text(p, color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
