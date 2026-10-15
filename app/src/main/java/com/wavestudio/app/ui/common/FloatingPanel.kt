package com.wavestudio.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * A panel that floats above its parent: drag the title bar to move it, drag the corner handle to resize it, collapse it
 * to the title bar or close it. Position and size survive rotation (rememberSaveable). Place it inside the parent [Box].
 */
@Composable
fun FloatingPanel(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val minW = with(density) { 240.dp.toPx() }
    val minH = with(density) { 150.dp.toPx() }
    val titleH = 40.dp
    BoxWithConstraints(modifier.fillMaxSize()) {
        val pw = with(density) { maxWidth.toPx() }
        val ph = with(density) { maxHeight.toPx() }
        var x by rememberSaveable { mutableStateOf(Float.NaN) }
        var y by rememberSaveable { mutableStateOf(0f) }
        var w by rememberSaveable { mutableStateOf(0f) }
        var h by rememberSaveable { mutableStateOf(0f) }
        var collapsed by rememberSaveable { mutableStateOf(false) }
        if (x.isNaN()) {     // first layout: bottom-right quarter-ish
            val r = com.wavestudio.app.project.PanelGeometry.clamp(com.wavestudio.app.project.PanelGeometry.Rect(pw * 0.08f, ph * 0.12f, pw * 0.84f, ph * 0.5f), pw, ph, minW, minH)
            x = r.x; y = r.y; w = r.w; h = r.h
        }
        val cur = com.wavestudio.app.project.PanelGeometry.clamp(com.wavestudio.app.project.PanelGeometry.Rect(x, y, w, h), pw, ph, minW, minH)
        val curState = rememberUpdatedState(cur)
        val titlePx = with(density) { titleH.toPx() }
        val shownH = if (collapsed) titlePx else cur.h

        Surface(
            modifier = Modifier.offset { IntOffset(cur.x.roundToInt(), cur.y.roundToInt()) }
                .size(with(density) { cur.w.toDp() }, with(density) { shownH.toDp() }),
            shape = RoundedCornerShape(12.dp), tonalElevation = 12.dp, shadowElevation = 12.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().height(titleH).background(MaterialTheme.colorScheme.primaryContainer)
                        .pointerInput(pw, ph) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val n = com.wavestudio.app.project.PanelGeometry.move(curState.value, drag.x, drag.y, pw, ph, minW, minH)
                                x = n.x; y = n.y; w = n.w; h = n.h
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DragHandle, "Drag to move", modifier = Modifier.padding(start = 8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f).padding(start = 6.dp))
                    IconButton(onClick = { collapsed = !collapsed }, modifier = Modifier.size(36.dp)) {
                        Icon(if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess, if (collapsed) "Expand" else "Collapse", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                if (!collapsed) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        content()
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(32.dp)
                                .pointerInput(pw, ph) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        val r = com.wavestudio.app.project.PanelGeometry.resize(curState.value, drag.x, drag.y, pw, ph, minW, minH)
                                        x = r.x; y = r.y; w = r.w; h = r.h
                                    }
                                },
                            contentAlignment = Alignment.BottomEnd
                        ) { Icon(Icons.Default.OpenInFull, "Drag to resize", modifier = Modifier.padding(6.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}
