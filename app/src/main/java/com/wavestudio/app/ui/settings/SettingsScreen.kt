package com.wavestudio.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.wavestudio.app.engine.AudioEngineController
import com.wavestudio.app.project.AppSettingsData
import com.wavestudio.app.project.ThemeMode
import com.wavestudio.app.settings.AppSettings
import com.wavestudio.app.ui.common.levelToUnit
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import android.os.Build
import com.wavestudio.app.ui.common.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(val engine: AudioEngineController, val settings: AppSettings) : ViewModel()

/**
 * Only shows things that are real: the live state of the audio device as reported by the engine, and where data lives.
 * (Earlier versions had switches here that were not connected to anything; they were removed.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val d by vm.engine.device.collectAsState()
    val st by vm.settings.data.collectAsState()
    val meters by vm.engine.meters.collectAsState()
    val upd = vm.settings::update
    val ctx = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
        })
    }) { pad ->
        Column(
            modifier = Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard("Appearance") {
                Text("Theme", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (m, label) ->
                        FilterChip(selected = st.themeMode == m, onClick = { upd { it.copy(themeMode = m) } }, label = { Text(label) })
                    }
                }
                Text("Accent colour", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppSettingsData.ACCENTS.forEachIndexed { i, a ->
                        val sel = st.accentIndex == i
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(a.argb))
                            .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { upd { it.copy(accentIndex = i) } })
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    SettingSwitch("Use wallpaper colours (Android 12+)", st.dynamicColor) { v -> upd { it.copy(dynamicColor = v) } }
                SettingSwitch("Keep the screen on while playing or recording", st.keepScreenOn) { v -> upd { it.copy(keepScreenOn = v) } }
            }
            SectionCard("Recording input") {
                Text("These settings change the signal that is metered, monitored AND written to the take file. Android's own processing (noise suppression, AGC) is requested off, so what you record is the microphone signal.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Microphone", style = MaterialTheme.typography.labelMedium)
                var devices by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(listInputDevices(ctx)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(selected = st.inputDeviceId == 0, onClick = { upd { it.copy(inputDeviceId = 0) } }, label = { Text("Automatic") })
                    devices.forEach { dv ->
                        FilterChip(selected = st.inputDeviceId == dv.id, onClick = { upd { it.copy(inputDeviceId = dv.id) } }, label = { Text(dv.label) })
                    }
                    OutlinedButton(onClick = { devices = listInputDevices(ctx) }) { Text("Rescan") }
                }
                val active = devices.firstOrNull { it.id == d.inputDeviceId }
                Text(if (d.inputRunning) "In use: ${active?.label ?: "system default"}" else "No input stream is open.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (st.inputDeviceId != 0 && devices.none { it.id == st.inputDeviceId })
                    Text("The chosen microphone is not connected; the system default is used instead.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text("Switching is refused while a take is being recorded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("File format", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(16 to "16-bit", 24 to "24-bit", 32 to "32-bit float").forEach { (b, label) ->
                        FilterChip(selected = st.recordBitDepth == b, onClick = { upd { it.copy(recordBitDepth = b) } }, label = { Text(label) })
                    }
                }
                Text("Input gain: %+.1f dB".format(st.inputGainDb), style = MaterialTheme.typography.labelMedium)
                Slider(value = st.inputGainDb, onValueChange = { v -> upd { it.copy(inputGainDb = v) } }, valueRange = -24f..24f)
                Text("Low-cut filter", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppSettingsData.LOW_CUTS.forEach { hz ->
                        FilterChip(selected = st.inputLowCutHz == hz, onClick = { upd { it.copy(inputLowCutHz = hz) } }, label = { Text(if (hz == 0) "Off" else "$hz Hz") })
                    }
                }
                SettingSwitch("Remove DC offset", st.removeDc) { v -> upd { it.copy(removeDc = v) } }
                Text("Input level", style = MaterialTheme.typography.labelMedium)
                if (!d.inputRunning) Text("No input stream is open (microphone permission missing or no input device).", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else {
                    val peak = meters.inputPeak
                    LinearProgressIndicator(
                        progress = levelToUnit(peak), modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = if (peak >= 0.98f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Clipped input samples: ${meters.inputClips}", modifier = Modifier.weight(1f),
                            color = if (meters.inputClips > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        OutlinedButton(onClick = { vm.engine.resetInputClips() }) { Text("Reset") }
                    }
                    Text("Speak or play at your loudest: aim for peaks around the upper third of the bar with no clipped samples.", style = MaterialTheme.typography.bodySmall)
                }
            }
            SectionCard("Export defaults") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !st.exportFlac, onClick = { upd { it.copy(exportFlac = false) } }, label = { Text("WAV") })
                    FilterChip(selected = st.exportFlac, onClick = { upd { it.copy(exportFlac = true) } }, label = { Text("FLAC") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(16, 24, 32).filter { !(st.exportFlac && it == 32) }.forEach { b ->
                        FilterChip(selected = st.exportBitDepth == b, onClick = { upd { it.copy(exportBitDepth = b) } }, label = { Text(if (b == 32) "32-bit float" else "$b-bit") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(44100, 48000, 96000).forEach { r ->
                        FilterChip(selected = st.exportSampleRate == r, onClick = { upd { it.copy(exportSampleRate = r) } }, label = { Text("${r / 1000f} kHz") })
                    }
                }
                Text("Used as the starting choice in the Export dialog.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard("Audio device (live)") {
                if (!d.outputRunning) Text("Output is not running", color = MaterialTheme.colorScheme.error)
                else {
                    Text("Sample rate: ${d.sampleRate} Hz")
                    Text("Burst: ${d.framesPerBurst} frames · buffer: ${d.bufferFrames} frames")
                    Text("Estimated output latency: %.1f ms".format(d.estOutputLatencyMs))
                }
                Text(if (d.inputRunning) "Input: ${d.inputChannels} channel(s), est. latency %.1f ms".format(d.estInputLatencyMs)
                     else "Input: closed (microphone permission missing or no input device)")
                Text("Latency values are estimates from buffer sizes. They differ between devices, routes (speaker, wired, Bluetooth) and Android versions.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard("Health") {
                Text("Output underruns (xruns): ${d.xruns}")
                Text("Input overruns: ${d.inputUnderruns}")
                Text("Late render blocks: ${d.underrunBlocks}")
                Text("Peak render load: ${(d.maxRenderLoad * 100).toInt()}% of buffer time")
                Text("Stream restarts (route changes / errors): ${d.restarts}")
                if (d.lastError != 0) Text("Last stream error code: ${d.lastError}", color = MaterialTheme.colorScheme.error)
            }
            SectionCard("Storage") {
                Text("Projects are stored privately on this device:")
                Text(ctx.filesDir.absolutePath + "/projects/<id>/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Each project folder holds project.json (+ project.json.bak), media/ (imports) and recordings/ (takes). " +
                    "Changes are saved automatically a moment after every edit.", style = MaterialTheme.typography.bodySmall)
            }
            SectionCard("About") {
                Text("WaveStudio works completely offline: the app does not request the internet permission.")
                Text("Exports are WAV (16/24-bit PCM or 32-bit float) or FLAC (16/24-bit).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
