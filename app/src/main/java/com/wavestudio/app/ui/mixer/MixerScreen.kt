package com.wavestudio.app.ui.mixer

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wavestudio.app.domain.Track
import com.wavestudio.app.ui.mixer.MixerPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixerScreen(onBack: () -> Unit) {
    // Standalone mixer view — real implementation would read state from VM
    val demo = (1..8).map { Track(id = it, name = "Track $it") }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Mixer") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
            })
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            MixerPanel(
                tracks = demo, peak = 0.5f, rms = 0.3f,
                onVolume = {_,_->}, onPan = {_,_->}, onMute = {}, onSolo = {}
            )
        }
    }
}
