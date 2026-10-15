package com.wavestudio.app.ui.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavestudio.app.domain.EffectType
import com.wavestudio.app.ui.common.Knob
import com.wavestudio.app.ui.common.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectsScreen(trackId: Int, onBack: () -> Unit) {
    var chain by remember {
        mutableStateOf(
            listOf(
                EffectSlot(type = EffectType.EQ, enabled = true, name = "Parametric EQ"),
                EffectSlot(type = EffectType.COMPRESSOR, enabled = true, name = "Compressor"),
                EffectSlot(type = EffectType.LIMITER, enabled = false, name = "Limiter")
            )
        )
    }
    var selected by remember { mutableStateOf(chain.firstOrNull()) }
    var showBrowser by remember { mutableStateOf(false) }
    var mode by remember { mutableIntStateOf(0) }
    val offsets = remember { mutableStateMapOf<Int, Offset>() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Effects — Track $trackId") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { showBrowser = true }) { Icon(Icons.Default.Add, "Add") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = mode) {
                Tab(selected = mode == 0, onClick = { mode = 0 }, text = { Text("Rack") })
                Tab(selected = mode == 1, onClick = { mode = 1 }, text = { Text("Overview (movable)") })
            }
            if (mode == 1) {
                EffectOverviewPanel(chain, offsets, onResize = { canvasSize = it })
            } else {
                Row(Modifier.fillMaxSize()) {
                    // Chain list
                    LazyColumn(modifier = Modifier.width(200.dp).fillMaxHeight().padding(8.dp)) {
                        items(chain) { slot ->
                            ElevatedCard(
                                onClick = { selected = slot },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (selected == slot) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                                    Icon(iconFor(slot.type), null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(slot.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text(slot.type.name, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = slot.enabled, onCheckedChange = {
                                        chain = chain.map { if (it === slot) it.copy(enabled = !it.enabled) else it }
                                    })
                                }
                            }
                        }
                        item {
                            OutlinedButton(onClick = { showBrowser = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add Effect", fontSize = 12.sp)
                            }
                        }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)))
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                        selected?.let { EffectDetail(it) }
                            ?: Text("Select an effect to edit", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (showBrowser) {
        EffectBrowserDialog(
            onDismiss = { showBrowser = false },
            onAdd = { type ->
                chain = chain + EffectSlot(
                    type, true,
                    type.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
                )
                showBrowser = false
            }
        )
    }
}

data class EffectSlot(val type: EffectType, val enabled: Boolean, val name: String)

@Composable
fun EffectOverviewPanel(
    chain: List<EffectSlot>,
    offsets: MutableMap<Int, Offset>,
    onResize: (IntSize) -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .onSizeChanged(onResize)
    ) {
        chain.forEachIndexed { idx, slot ->
            var ox by remember { mutableFloatStateOf(offsets[idx]?.x ?: (40f + idx * 170f)) }
            var oy by remember { mutableFloatStateOf(offsets[idx]?.y ?: 80f) }
            Surface(
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.medium,
                color = if (slot.enabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .offset { androidx.compose.ui.IntOffset(ox.toInt(), oy.toInt()) }
                    .width(150.dp).height(90.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            ox = (ox + drag.x).coerceIn(0f, 2000f)
                            oy = (oy + drag.y).coerceIn(0f, 2000f)
                            offsets[idx] = Offset(ox, oy)
                        }
                    }
            ) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(iconFor(slot.type), null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(slot.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (slot.enabled) "ENABLED" else "BYPASSED",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun EffectDetail(slot: EffectSlot) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(slot.type), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(slot.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        val defaults = when (slot.type) {
            EffectType.EQ -> listOf(
                KnobParam("Low", -24f..24f, 0f, "dB"),
                KnobParam("Lo-Mid", -24f..24f, 0f, "dB"),
                KnobParam("Mid", -24f..24f, 0f, "dB"),
                KnobParam("Hi-Mid", -24f..24f, 0f, "dB"),
                KnobParam("High", -24f..24f, 0f, "dB"),
                KnobParam("Q", 0.1f..10f, 1.4f, "")
            )
            EffectType.COMPRESSOR -> listOf(
                KnobParam("Thresh", -60f..0f, -18f, "dB"),
                KnobParam("Ratio", 1f..20f, 4f, ":1"),
                KnobParam("Attack", 0.1f..200f, 5f, "ms"),
                KnobParam("Release", 10f..2000f, 100f, "ms"),
                KnobParam("Knee", 0f..24f, 6f, "dB"),
                KnobParam("Makeup", 0f..24f, 0f, "dB")
            )
            EffectType.REVERB -> listOf(
                KnobParam("Size", 0f..1f, 0.7f, ""),
                KnobParam("Damp", 0f..1f, 0.5f, ""),
                KnobParam("Wet", 0f..1f, 0.3f, ""),
                KnobParam("Dry", 0f..1f, 1f, ""),
                KnobParam("Width", 0f..1f, 1f, ""),
                KnobParam("Pre-Dly", 0f..100f, 10f, "ms")
            )
            EffectType.DELAY -> listOf(
                KnobParam("Time", 1f..2000f, 400f, "ms"),
                KnobParam("FB", 0f..1f, 0.4f, ""),
                KnobParam("Mix", 0f..1f, 0.3f, ""),
                KnobParam("HPF", 20f..2000f, 200f, "Hz"),
                KnobParam("LPF", 1000f..20000f, 12000f, "Hz"),
                KnobParam("Ping-Pong", 0f..1f, 0f, "")
            )
            else -> listOf(
                KnobParam("Drive", 0f..100f, 20f, ""),
                KnobParam("Mix", 0f..1f, 0.5f, ""),
                KnobParam("Gain", -24f..24f, 0f, "dB"),
                KnobParam("Tone", 0f..1f, 0.5f, ""),
                KnobParam("Rate", 0f..10f, 1f, "Hz"),
                KnobParam("Depth", 0f..1f, 0.5f, "")
            )
        }
        LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(defaults) { k ->
                var v by remember { mutableFloatStateOf(k.initial) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Knob(value = v, range = k.range, label = k.name, size = 56.dp, onValueChange = { v = it },
                        formatter = { "${"%.1f".format(it)}${k.unit}" })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        SectionCard("Preset") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Default", "Vocal", "Drums", "Bass", "Master").forEach {
                    FilterChip(selected = it == "Default", onClick = {}, label = { Text(it, fontSize = 11.sp) })
                }
            }
        }
    }
}

data class KnobParam(val name: String, val range: ClosedFloatingPointRange<Float>, val initial: Float, val unit: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectBrowserDialog(onDismiss: () -> Unit, onAdd: (EffectType) -> Unit) {
    val all = EffectType.values().toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Effect") },
        text = {
            LazyVerticalGrid(columns = GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(all) { t ->
                    ElevatedCard(onClick = { onAdd(t) }, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                            Icon(iconFor(t), null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(t.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

fun iconFor(t: EffectType): ImageVector = when (t) {
    EffectType.EQ -> Icons.Default.Equalizer
    EffectType.COMPRESSOR -> Icons.Default.Compress
    EffectType.LIMITER -> Icons.Default.Block
    EffectType.REVERB -> Icons.Default.SurroundSound
    EffectType.DELAY -> Icons.Default.Schedule
    EffectType.CHORUS -> Icons.Default.Groups
    EffectType.FLANGER -> Icons.Default.Waves
    EffectType.PHASER -> Icons.Default.FilterAlt
    EffectType.DISTORTION -> Icons.Default.ElectricBolt
    EffectType.BITCRUSHER -> Icons.Default.Memory
    EffectType.FILTER -> Icons.Default.Tune
    EffectType.NOISE_GATE -> Icons.Default.DoNotDisturbOn
    EffectType.DE_ESSER -> Icons.Default.RecordVoiceOver
    EffectType.TUNER -> Icons.Default.MusicNote
    EffectType.GAIN -> Icons.Default.Tune
    EffectType.INVERT -> Icons.Default.SwapHoriz
}
