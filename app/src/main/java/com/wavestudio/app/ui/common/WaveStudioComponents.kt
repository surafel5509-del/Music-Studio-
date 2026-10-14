package com.wavestudio.app.ui.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavestudio.app.ui.theme.WaveStudioTheme
import kotlin.math.*

@Composable
fun VuMeter(
    peak: Float,
    rms: Float,
    modifier: Modifier = Modifier,
    vertical: Boolean = true,
    label: String? = null
) {
    val colors = WaveStudioTheme.colors
    val peakDb = 20f * log10(peak.coerceAtLeast(1e-9f))
    val rmsDb = 20f * log10(rms.coerceAtLeast(1e-9f))
    val peakNorm = ((peakDb + 60f) / 60f).coerceIn(0f, 1f)
    val rmsNorm = ((rmsDb + 60f) / 60f).coerceIn(0f, 1f)
    val animatedRms by animateFloatAsState(targetValue = rmsNorm, animationSpec = tween(60))
    val animatedPeak by animateFloatAsState(targetValue = peakNorm, animationSpec = tween(40))

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (vertical) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(animatedRms)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    colors.meterGood,
                                    colors.meterGood.copy(0.6f)
                                )
                            )
                        )
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val y = size.height * (1 - animatedPeak)
                    val c = if (peakNorm > 0.9f) colors.meterBad else if (peakNorm > 0.75f) colors.meterWarn else colors.meterGood
                    drawLine(c, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedRms)
                        .align(Alignment.CenterStart)
                        .background(
                            Brush.horizontalGradient(
                                listOf(colors.meterGood, colors.meterWarn, colors.meterBad),
                                endX = 400f
                            )
                        )
                )
            }
        }
        label?.let { Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
    }
}

@Composable
fun Knob(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit,
    label: String? = null,
    size: Dp = 48.dp,
    formatter: (Float) -> String = { "%.1f".format(it) }
) {
    val normalized = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    var startNormalized by remember { mutableFloatStateOf(normalized) }
    var startY by remember { mutableFloatStateOf(0f) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { startNormalized = normalized; startY = it.y },
                        onVerticalDrag = { _, drag ->
                            val delta = -drag / size.height.toPx()
                            val n = (startNormalized + delta).coerceIn(0f, 1f)
                            onValueChange(range.start + n * (range.endInclusive - range.start))
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val angle = (-135f + normalized * 270f) * kotlin.math.PI.toFloat() / 180f
                val cx = size.width / 2
                val cy = size.height / 2
                val r = size.minDimension / 2 - 4.dp.toPx()
                drawCircle(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), radius = r*0.75f)
                drawLine(
                    MaterialTheme.colorScheme.primary,
                    Offset(cx, cy),
                    Offset(cx + kotlin.math.cos(angle)*r*0.8f, cy + kotlin.math.sin(angle)*r*0.8f),
                    strokeWidth = 3.dp.toPx()
                )
                drawCircle(MaterialTheme.colorScheme.onSurface, radius = 3.dp.toPx())
            }
        }
        label?.let {
            Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(formatter(value), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun Fader(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit,
    label: String = ""
) {
    val normalized = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f,1f)
    Column(modifier = modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (label.isNotEmpty()) Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
                .width(18.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, drag ->
                        val delta = -drag / size.height
                        val n = (normalized + delta).coerceIn(0f,1f)
                        onValueChange(range.start + n*(range.endInclusive - range.start))
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .offset(y = ((1f - normalized) * 200f).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .align(Alignment.TopCenter)
            )
        }
        val db = 20*log10(value.coerceAtLeast(0.0001f))
        Text("%.1f".format(db) + "dB", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean = false,
    enabled: Boolean = true,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (active) activeColor else MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun WaveformMini(
    peakData: FloatArray,
    modifier: Modifier = Modifier,
    color: Color = WaveStudioTheme.colors.waveform,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    Canvas(modifier = modifier.background(backgroundColor)) {
        val w = size.width
        val h = size.height
        val n = peakData.size / 2
        if (n == 0) return@Canvas
        for (i in 0 until min(n, w.toInt())) {
            val mn = peakData[i*2]
            val mx = peakData[i*2+1]
            val y1 = h/2 - mx * h/2
            val y2 = h/2 - mn * h/2
            drawLine(color, Offset(i.toFloat(), y1), Offset(i.toFloat(), y2.coerceAtLeast(y1+1)), strokeWidth = 1f)
        }
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(icon, text, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(text, fontSize = 10.sp, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
