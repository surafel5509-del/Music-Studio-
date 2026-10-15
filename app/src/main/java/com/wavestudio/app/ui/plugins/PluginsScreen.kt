package com.wavestudio.app.ui.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavestudio.app.audio.plugins.PluginManager
import com.wavestudio.app.domain.PluginCategory
import com.wavestudio.app.domain.PluginInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<PluginCategory?>(null) }
    val plugins = remember {
        listOf(
            PluginInfo("builtin.eq", "Parametric EQ", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
            PluginInfo("builtin.comp", "Compressor", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
            PluginInfo("builtin.limiter", "Limiter", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
            PluginInfo("builtin.reverb", "Reverb", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
            PluginInfo("builtin.delay", "Stereo Delay", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
            PluginInfo("builtin.chorus", "Chorus", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
            PluginInfo("builtin.flanger", "Flanger", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
            PluginInfo("builtin.phaser", "Phaser", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
            PluginInfo("builtin.distortion", "Distortion", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
            PluginInfo("builtin.bitcrusher", "Bit Crusher", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
            PluginInfo("builtin.filter", "Filter", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
            PluginInfo("builtin.gate", "Noise Gate", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
            PluginInfo("builtin.deesser", "De-Esser", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
            PluginInfo("builtin.tuner", "Tuner", "WaveStudio", "1.0", PluginCategory.UTILITY, isBuiltIn = true)
        )
    }
    val filtered = plugins.filter {
        (category == null || it.category == category) &&
        (query.isBlank() || it.name.contains(query, ignoreCase = true) || it.vendor.contains(query, ignoreCase = true))
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Plugins") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
            }, actions = {
                IconButton(onClick = {}) { Icon(Icons.Default.InstallMobile, "Install") }
            })
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search plugins") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All") })
                FilterChip(selected = category == PluginCategory.EFFECT, onClick = { category = PluginCategory.EFFECT }, label = { Text("Effects") })
                FilterChip(selected = category == PluginCategory.INSTRUMENT, onClick = { category = PluginCategory.INSTRUMENT }, label = { Text("Instruments") })
                FilterChip(selected = category == PluginCategory.UTILITY, onClick = { category = PluginCategory.UTILITY }, label = { Text("Utilities") })
            }
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered) { p -> PluginCard(p) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginCard(p: PluginInfo) {
    ElevatedCard(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(p.vendor + " · v" + p.version, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            if (p.isBuiltIn) {
                Spacer(Modifier.height(4.dp))
                SuggestionChip(onClick = {}, label = { Text("Built-in", fontSize = 9.sp) })
            }
        }
    }
}
