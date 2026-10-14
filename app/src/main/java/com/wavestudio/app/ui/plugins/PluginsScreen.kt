package com.wavestudio.app.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.wavestudio.app.session.ProjectSession
import com.wavestudio.app.ui.effects.effectTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PluginsViewModel @Inject constructor(private val session: ProjectSession) : ViewModel() {
    /** The effects the native engine can actually create (queried from the engine, not a hard-coded catalogue). */
    fun builtInEffects(): List<String> = session.effectTypes()
}

/**
 * Lists the built-in effects that the audio engine really provides. Third-party plugin formats are not hosted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(onBack: () -> Unit, vm: PluginsViewModel = hiltViewModel()) {
    val effects = remember { vm.builtInEffects() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Built-in effects") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            Text(
                "These effects run inside WaveStudio's native audio engine and can be added to any track or the master from the editor. " +
                    "VST3, AU, CLAP and Android plugin hosting are not supported in this version.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(effects) { type ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(effectTitle(type), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("WaveStudio · built-in", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}
