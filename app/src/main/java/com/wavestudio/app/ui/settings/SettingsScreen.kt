package com.wavestudio.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wavestudio.app.ui.common.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var themeDark by remember { mutableStateOf(true) }
    var sampleRate by remember { mutableStateOf(48000) }
    var bufferSize by remember { mutableStateOf(256) }
    var bitDepth by remember { mutableStateOf(24) }
    var lowLatency by remember { mutableStateOf(true) }
    var autoSave by remember { mutableStateOf(true) }
    var snapToGrid by remember { mutableStateOf(true) }
    var autoBackup by remember { mutableStateOf(true) }
    var metronomeDefaultOn by remember { mutableStateOf(false) }
    var defaultBpm by remember { mutableStateOf(120) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
        })
    }) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard("Appearance") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Dark theme", modifier = Modifier.weight(1f))
                    Switch(checked = themeDark, onCheckedChange = { themeDark = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Accent color", modifier = Modifier.weight(1f))
                    // Accent swatches would go here; using default blue for simplicity
                    Text("Blue", color = MaterialTheme.colorScheme.primary)
                }
            }

            SectionCard("Audio") {
                Text("Sample rate", style = MaterialTheme.typography.labelMedium)
                Row {
                    listOf(44100, 48000, 88200, 96000, 192000).forEach { sr ->
                        FilterChip(
                            selected = sampleRate == sr, onClick = { sampleRate = sr },
                            label = { Text("${sr/1000}k") },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Bit depth", style = MaterialTheme.typography.labelMedium)
                Row {
                    listOf(16, 24, 32).forEach { b ->
                        FilterChip(
                            selected = bitDepth == b, onClick = { bitDepth = b },
                            label = { Text("${b}bit") },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Buffer size", style = MaterialTheme.typography.labelMedium)
                Slider(value = bufferSize.toFloat(), onValueChange = { bufferSize = it.toInt() },
                    valueRange = 32f..2048f, steps = 7)
                Text("${bufferSize} frames", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Low-latency mode", modifier = Modifier.weight(1f))
                    Switch(checked = lowLatency, onCheckedChange = { lowLatency = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bluetooth microphone support", modifier = Modifier.weight(1f))
                    Switch(checked = true, onCheckedChange = {})
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("USB audio interface", modifier = Modifier.weight(1f))
                    Switch(checked = true, onCheckedChange = {})
                }
            }

            SectionCard("Project Defaults") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Snap to grid", modifier = Modifier.weight(1f))
                    Switch(checked = snapToGrid, onCheckedChange = { snapToGrid = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-save", modifier = Modifier.weight(1f))
                    Switch(checked = autoSave, onCheckedChange = { autoSave = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Incremental backup", modifier = Modifier.weight(1f))
                    Switch(checked = autoBackup, onCheckedChange = { autoBackup = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Metronome on by default", modifier = Modifier.weight(1f))
                    Switch(checked = metronomeDefaultOn, onCheckedChange = { metronomeDefaultOn = it })
                }
                Text("Default BPM", style = MaterialTheme.typography.labelMedium)
                Slider(value = defaultBpm.toFloat(), onValueChange = { defaultBpm = it.toInt() }, valueRange = 40f..240f)
                Text("$defaultBpm BPM", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionCard("Storage") {
                Text("Project folder", style = MaterialTheme.typography.bodyMedium)
                Text("/Android/media/com.wavestudio.app/projects",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {}) { Text("Clear cache") }
            }

            SectionCard("About") {
                Text("WaveStudio v1.0.0", style = MaterialTheme.typography.titleMedium)
                Text("A fully offline, professional-grade music editor for Android.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("No internet connection required. All processing happens locally on your device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {}) { Text("View licenses") }
                TextButton(onClick = {}) { Text("Offline help & tutorials") }
            }
        }
    }
}
