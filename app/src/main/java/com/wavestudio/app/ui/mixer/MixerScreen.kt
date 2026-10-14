package com.wavestudio.app.ui.mixer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.wavestudio.app.ui.editor.EditorViewModel

/** Full-screen mixer for the project that is open in the editor (same live session, same meters). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixerScreen(onBack: () -> Unit, onOpenEffects: (Int) -> Unit, vm: EditorViewModel = hiltViewModel()) {
    val doc by vm.doc.collectAsState()
    val meters by vm.meters.collectAsState()
    val trackMeters by vm.trackMeters.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Mixer — ${doc.name}") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            })
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            MixerPanel(
                tracks = doc.tracks, trackMeters = trackMeters, master = meters, masterVolume = doc.masterVolume,
                onVolume = vm::setTrackVolume, onPan = vm::setTrackPan,
                onMute = vm::toggleMute, onSolo = vm::toggleSolo,
                onMasterVolume = vm::setMasterVolume, onEffects = onOpenEffects,
                buses = doc.buses, onSend = vm::setTrackSend, onBusLevel = vm::setBusLevel,
                onAddBus = vm::addBus, onRemoveBus = vm::removeBus
            )
        }
    }
}
