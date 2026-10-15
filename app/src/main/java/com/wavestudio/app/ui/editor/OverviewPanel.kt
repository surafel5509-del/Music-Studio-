package com.wavestudio.app.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavestudio.app.project.EffectCatalog
import com.wavestudio.app.project.EffectDoc
import com.wavestudio.app.project.ProjectDoc
import com.wavestudio.app.ui.mixer.MiniToggle
import kotlin.math.log10

private class OverviewRow(val target: Int, val name: String, val effects: List<EffectDoc>)

/**
 * Content of the movable, resizable overview box. "Effects" lists every insert chain (tracks, aux buses, master) as chips:
 * tap a chip to bypass/enable that effect, tap the sliders icon to open the full effect editor. "Mixer" has volume,
 * mute and solo for every track plus the master. Everything is wired to the same edits as the main editor and mixer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewPanel(doc: ProjectDoc, vm: EditorViewModel, onOpenEffects: (Int) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Effects", fontSize = 12.sp) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Mixer", fontSize = 12.sp) })
        }
        if (tab == 0) {
            val rows = buildList {
                add(OverviewRow(0, "Master", doc.masterEffects))
                doc.tracks.forEach { add(OverviewRow(it.id, it.name, it.effects)) }
                doc.buses.forEach { add(OverviewRow(-(it.index + 1), it.name, it.effects)) }
            }
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                items(rows, key = { it.target }) { r ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(r.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(64.dp))
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (r.effects.isEmpty()) Text("no effects", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            r.effects.forEach { fx ->
                                FilterChip(selected = !fx.bypass, onClick = { vm.setEffectBypass(fx.id, !fx.bypass) },
                                    label = { Text(EffectCatalog.title(fx.type), fontSize = 11.sp) })
                            }
                        }
                        IconButton(onClick = { onOpenEffects(r.target) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Tune, "Open ${r.name} effects") }
                    }
                }
                item { Text("Filled chip = active. Tap to bypass.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                item {
                    LevelRow("Master", doc.masterVolume, 2f, onVolume = vm::setMasterVolume)
                }
                items(doc.tracks, key = { it.id }) { t ->
                    LevelRow(t.name, t.volume, 2f, onVolume = { vm.setTrackVolume(t.id, it) }, mute = t.mute, solo = t.solo,
                        onMute = { vm.toggleMute(t.id) }, onSolo = { vm.toggleSolo(t.id) })
                }
            }
        }
    }
}

@Composable
private fun LevelRow(
    name: String, volume: Float, max: Float, onVolume: (Float) -> Unit,
    mute: Boolean = false, solo: Boolean = false, onMute: (() -> Unit)? = null, onSolo: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
        Text(name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(64.dp))
        Slider(value = volume.coerceIn(0f, max), onValueChange = onVolume, valueRange = 0f..max, modifier = Modifier.weight(1f))
        val db = if (volume <= 0.0001f) "-∞" else "%+.1f".format(20f * log10(volume))
        Text(db, fontSize = 10.sp, modifier = Modifier.width(38.dp))
        if (onMute != null) MiniToggle("M", mute, Color(0xFFE53935), onMute)
        if (onSolo != null) { androidx.compose.foundation.layout.Spacer(Modifier.width(3.dp)); MiniToggle("S", solo, Color(0xFFFFA000), onSolo) }
    }
}
