package com.wavestudio.app.ui.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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

/** dB position (0..1, -60 dB..0 dB) of a linear level. NaN / infinite / negative input maps to 0 (never throws). */
fun levelToUnit(level: Float): Float {
    if (level.isNaN() || level <= 1e-6f) return 0f
    if (level.isInfinite()) return 1f
    return ((20f * log10(level) + 60f) / 60f).coerceIn(0f, 1f)
}

/** Level meter drawn on a canvas. All inputs are sanitized: a NaN from the engine must never reach layout code. */
@Composable
fun VuMeter(
    peak: Float,
    rms: Float,
    modifier: Modifier = Modifier,
    vertical: Boolean = true,
    label: String? = null
) {
    val colors = WaveStudioTheme.colors
    val peakNorm = levelToUnit(peak)
    val rmsNorm = levelToUnit(rms)
    val animatedRms by animateFloatAsState(targetValue = rmsNorm, animationSpec = tween(60), label = "rms")
    val animatedPeak by animateFloatAsState(targetValue = peakNorm, animationSpec = tween(40), label = "peak")
    val back = MaterialTheme.colorScheme.surfaceVariant
    val good = colors.meterGood; val warn = colors.meterWarn; val bad = colors.meterBad
    val peakColor = if (peakNorm > 0.97f) bad else if (peakNorm > 0.85f) warn else good

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = (if (vertical) Modifier.fillMaxHeight().width(12.dp) else Modifier.fillMaxWidth().height(10.dp))
                .clip(RoundedCornerShape(3.dp))
        ) {
            drawRect(back)
            val r = animatedRms.coerceIn(0f, 1f); val p = animatedPeak.coerceIn(0f, 1f)
            if (vertical) {
                val top = size.height * (1f - r)
                drawRect(Brush.verticalGradient(listOf(bad, warn, good), startY = 0f, endY = size.height.coerceAtLeast(1f)),
                    topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, size.height - top))
                val y = size.height * (1f - p)
                drawLine(peakColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
            } else {
                drawRect(Brush.horizontalGradient(listOf(good, warn, bad), startX = 0f, endX = size.width.coerceAtLeast(1f)),
                    size = androidx.compose.ui.geometry.Size(size.width * r, size.height))
                val x = size.width * p
                drawLine(peakColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
        label?.let { Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
    }
}

/**
 * Rotary knob: vertical drag (about four knob heights for the full range), double-tap resets to [defaultValue].
 * The drag handler always works from the *current* value and the *current* callback (the pointer-input block is
 * created once, so it reads them through rememberUpdatedState; capturing them directly made drags start from a stale
 * value and send changes to the wrong parameter after the knob was reused for another one).
 */
@Composable
fun Knob(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit,
    label: String? = null,
    size: Dp = 48.dp,
    formatter: (Float) -> String = { "%.1f".format(it) },
    defaultValue: Float? = null
) {
    val span = range.endInclusive - range.start
    val safeValue = if (value.isNaN()) range.start else value
    val normalized = if (span > 0f) ((safeValue - range.start) / span).coerceIn(0f, 1f) else 0f
    val normalizedS = rememberUpdatedState(normalized)
    val onChangeS = rememberUpdatedState(onValueChange)
    val rangeS = rememberUpdatedState(range)
    val defaultS = rememberUpdatedState(defaultValue)
    val knobPrimary = MaterialTheme.colorScheme.primary
    val knobCenter = MaterialTheme.colorScheme.onSurface

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { defaultS.value?.let { d -> onChangeS.value(d) } })
                }
                .pointerInput(Unit) {
                    var start = 0f
                    detectVerticalDragGestures(
                        onDragStart = { start = normalizedS.value },
                        onVerticalDrag = { change, drag ->
                            val h = this.size.height.toFloat().coerceAtLeast(1f)
                            start = (start - drag / (h * 4f)).coerceIn(0f, 1f)
                            val r = rangeS.value
                            onChangeS.value(r.start + start * (r.endInclusive - r.start))
                            change.consume()
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val angle = (-135f + normalized * 270f) * kotlin.math.PI.toFloat() / 180f
                val cx = this.size.width / 2
                val cy = this.size.height / 2
                val r = this.size.minDimension / 2 - 4.dp.toPx()
                drawCircle(knobPrimary.copy(alpha = 0.2f), radius = (r * 0.75f).coerceAtLeast(0f))
                drawLine(
                    knobPrimary,
                    Offset(cx, cy),
                    Offset(cx + kotlin.math.cos(angle) * r * 0.8f, cy + kotlin.math.sin(angle) * r * 0.8f),
                    strokeWidth = 3.dp.toPx()
                )
                drawCircle(knobCenter, radius = 3.dp.toPx())
            }
        }
        label?.let {
            Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(formatter(safeValue), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
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
