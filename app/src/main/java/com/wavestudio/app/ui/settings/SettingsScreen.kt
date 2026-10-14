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
import com.wavestudio.app.ui.common.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(val engine: AudioEngineController) : ViewModel()

/**
 * Only shows things that are real: the live state of the audio device as reported by the engine, and where data lives.
 * (Earlier versions had switches here that were not connected to anything; they were removed.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val d by vm.engine.device.collectAsState()
    val ctx = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(title = { Text("Audio & storage") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
        })
    }) { pad ->
        Column(
            modifier = Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                Text("Exports are WAV (16/24-bit PCM or 32-bit float) only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
