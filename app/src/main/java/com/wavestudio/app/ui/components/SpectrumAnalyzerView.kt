package com.wavestudio.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wavestudio.app.ui.theme.WaveStudioTheme
import kotlin.math.max

@Composable
fun SpectrumAnalyzerView(
    bins: FloatArray,
    modifier: Modifier = Modifier
) {
    val colors = WaveStudioTheme.colors
    Canvas(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        val w = size.width
        val h = size.height
        val n = bins.size
        if (n == 0) return@Canvas
        val bw = w / n.toFloat()
        // Draw gradient fill
        for (i in 0 until n) {
            val db = bins[i].coerceIn(-80f, 0f)
            val norm = (db + 80f)/80f
            val bh = norm * h
            val color = when {
                norm > 0.85f -> colors.meterBad
                norm > 0.7f -> colors.meterWarn
                else -> Color(0xFF5DA9FF)
            }
            drawRect(
                brush = Brush.verticalGradient(listOf(color.copy(alpha=0.9f), color.copy(alpha=0.3f))),
                topLeft = Offset(i * bw, h - bh),
                size = androidx.compose.ui.geometry.Size(max(bw - 1.dp.toPx(), 1f), bh)
            )
        }
    }
}
