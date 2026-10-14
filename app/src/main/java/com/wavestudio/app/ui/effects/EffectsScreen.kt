package com.wavestudio.app.ui.effects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.wavestudio.app.project.EditOps
import com.wavestudio.app.project.EffectDoc
import com.wavestudio.app.project.NativeEffectInfo
import com.wavestudio.app.project.ProjectDoc
import com.wavestudio.app.session.ProjectSession
import com.wavestudio.app.ui.common.Knob
import com.wavestudio.app.ui.common.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class EffectsViewModel @Inject constructor(val session: ProjectSession) : ViewModel() {
    val doc = session.doc
    fun chain(d: ProjectDoc, trackId: Int): List<EffectDoc> =
        if (trackId == 0) d.masterEffects else if (trackId < 0) d.bus(-trackId - 1)?.effects ?: emptyList() else d.track(trackId)?.effects ?: emptyList()
    fun add(trackId: Int, type: String) { session.edit("Add effect") { EditOps.addEffect(it, trackId, type).first } }
    fun remove(effectId: Int) { session.edit("Remove effect") { EditOps.removeEffect(it, effectId) } }
    fun setBypass(effectId: Int, bypass: Boolean) { session.edit("Bypass") { EditOps.setEffectBypass(it, effectId, bypass) } }
    fun setParam(effectId: Int, paramId: String, v: Float) {
        session.edit("Effect parameter", "fx:$effectId:$paramId") { EditOps.setEffectParam(it, effectId, paramId, v) }
    }
    fun info(effectId: Int): NativeEffectInfo? = session.effectInfo(effectId)
    fun types(): List<String> = session.effectTypes()
}

fun effectTitle(type: String) = type.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** Insert effects of a track (or the master bus when [trackId] is 0, or an aux bus when it is -(index+1)). Parameters come from the native effect itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectsScreen(trackId: Int, onBack: () -> Unit, vm: EffectsViewModel = hiltViewModel()) {
    val doc by vm.doc.collectAsState()
    val chain = vm.chain(doc, trackId)
    var selectedId by remember { mutableStateOf<Int?>(null) }
    var showBrowser by remember { mutableStateOf(false) }
    val selected = chain.firstOrNull { it.id == selectedId } ?: chain.firstOrNull()
    val title = if (trackId == 0) "Master" else if (trackId < 0) doc.bus(-trackId - 1)?.name ?: "Bus" else doc.track(trackId)?.name ?: "Track"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Effects — $title") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { showBrowser = true }) { Icon(Icons.Default.Add, "Add effect") } }
            )
        }
    ) { pad ->
        Row(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(modifier = Modifier.width(220.dp).fillMaxHeight().padding(8.dp)) {
                items(chain, key = { it.id }) { fx ->
                    ElevatedCard(
                        onClick = { selectedId = fx.id },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (selected?.id == fx.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(effectTitle(fx.type), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (fx.bypass) "bypassed" else "active", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = !fx.bypass, onCheckedChange = { vm.setBypass(fx.id, !it) })
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { showBrowser = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.height(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add effect", fontSize = 12.sp)
                    }
                }
            }
            Divider(Modifier.width(1.dp).fillMaxHeight())
            Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                if (selected == null) Text("No effects yet. Tap + to add one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else EffectDetail(selected, vm, onRemoved = { selectedId = null })
            }
        }
    }

    if (showBrowser) {
        val types = remember { vm.types() }
        AlertDialog(
            onDismissRequest = { showBrowser = false },
            title = { Text("Add effect") },
            text = {
                LazyVerticalGrid(columns = GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(types) { t ->
                        ElevatedCard(onClick = { vm.add(trackId, t); showBrowser = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(effectTitle(t), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(14.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBrowser = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun EffectDetail(fx: EffectDoc, vm: EffectsViewModel, onRemoved: () -> Unit) {
    val info = remember(fx.id, fx.type) { vm.info(fx.id) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(effectTitle(fx.type), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (info != null) TextButton(onClick = { info.params.forEach { vm.setParam(fx.id, it.id, it.def) } }) { Text("Reset") }
            IconButton(onClick = { vm.remove(fx.id); onRemoved() }) { Icon(Icons.Default.Delete, "Remove effect") }
        }
        Spacer(Modifier.height(12.dp))
        if (info == null) {
            Text("This effect is not loaded in the audio engine.", color = MaterialTheme.colorScheme.error)
            return
        }
        SectionCard(title = "Parameters") {
            LazyVerticalGrid(columns = GridCells.Adaptive(96.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(320.dp)) {
                items(info.params, key = { it.id }) { p ->
                    val v = fx.params[p.id] ?: p.def
                    Knob(
                        value = v, range = p.min..p.max, label = p.name, size = 56.dp,
                        formatter = { "%.2f".format(it) + if (p.unit.isNotEmpty()) " ${p.unit}" else "" },
                        onValueChange = { vm.setParam(fx.id, p.id, it) }
                    )
                }
            }
        }
    }
}
