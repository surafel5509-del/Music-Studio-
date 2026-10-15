package com.wavestudio.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wavestudio.app.ui.theme.WaveStudioTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember(context) { SettingsRepository(context.applicationContext) }
    val settings by repo.settings.collectAsState(initial = AppSettings())
    WaveStudioTheme(darkTheme = settings.darkMode) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Settings") }, navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                })
            }
        ) { pad ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = "WaveStudio Pro Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                }

                item { SettingSection("Appearance") }
                item {
                    SettingToggle("Dark Mode", settings.darkMode) {
                        kotlinx.coroutines.MainScope().launch { repo.setDarkMode(it) }
                    }
                }
                item {
                    SettingToggle("Custom Accent Colors", settings.customColors) {
                        kotlinx.coroutines.MainScope().launch { repo.setCustomColors(it) }
                    }
                }
                item {
                    SettingToggle("Show Waveforms", settings.showWaveforms) {
                        kotlinx.coroutines.MainScope().launch { repo.setShowWaveforms(it) }
                    }
                }

                item { SettingSection("Audio Engine") }
                item {
                    val rates = listOf(44100, 48000, 88200, 96000)
                    SettingDropdown("Sample Rate", rates.map { "$it Hz" }, rates.indexOf(settings.sampleRate).coerceAtLeast(1)) { idx ->
                        kotlinx.coroutines.MainScope().launch { repo.setSampleRate(rates[idx]) }
                    }
                }
                item {
                    val bufs = listOf(64, 128, 256, 512, 1024)
                    SettingDropdown("Buffer Size (frames)", bufs.map { it.toString() }, bufs.indexOf(settings.bufferSize).coerceAtLeast(2)) { idx ->
                        kotlinx.coroutines.MainScope().launch { repo.setBufferSize(bufs[idx]) }
                    }
                }
                item {
                    SettingToggle("Metronome (Click Track)", settings.metronome) {
                        kotlinx.coroutines.MainScope().launch { repo.setMetronome(it) }
                    }
                }

                item { SettingSection("Project") }
                item {
                    SettingToggle("Auto-Save Every 30s", settings.autoSave) {
                        kotlinx.coroutines.MainScope().launch { repo.setAutoSave(it) }
                    }
                }

                item { SettingSection("About") }
                item {
                    InfoCard("WaveStudio Pro v2.0", "AAudio-native DAW engine • Jetpack Compose UI\nOffline only • Android 8.0+")
                }
                item {
                    InfoCard("Supported Formats", "Import: WAV, FLAC, MP3, M4A, OGG, MP4/MKV (video→audio)\nExport: WAV 16/24-bit, FLAC")
                }
            }
        }
    }
}

@Composable private fun SettingSection(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

@Composable private fun SettingToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable private fun SettingDropdown(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    var exp by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton(onClick = { exp = true }) {
                Text(options.getOrElse(selected) { options[0] })
            }
            DropdownMenu(expanded = exp, onDismissRequest = { exp = false }) {
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(text = { Text(o) }, onClick = { onPick(i); exp = false })
                }
            }
        }
    }
}

@Composable private fun InfoCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
