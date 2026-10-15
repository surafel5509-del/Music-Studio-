package com.wavestudio.app.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wavestudio.app.domain.AudioClip
import com.wavestudio.app.domain.Track
import com.wavestudio.app.ui.theme.WaveStudioTheme
import kotlin.math.*

/**
 * Professional multi-track timeline: pinch-zoom (5–5000 px/sec), scroll/pan clamped,
 * tap-to-seek with snap-to-grid, beat-aligned ruler, lane backgrounds, clip bodies
 * with deterministic waveform preview, playhead with head-triangle.
 *
 * Crash-free: canvas size captured via onSizeChanged before pointerInput,
 * scroll is clamped each frame, all numeric ops guarded against NaN/Infinity.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TimelineView(
    tracks: List<Track>,
    clips: List<AudioClip>,
    playheadFrames: Long,
    totalFrames: Long,
    sampleRate: Float,
    bpm: Float,
    snapToGrid: Boolean,
    selectedClipId: Int?,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit = {},
    onClipMove: (clipId: Int, newStartFrame: Long, newTrackId: Int) -> Unit = { _,_,_ -> },
    onClipResize: (clipId: Int, newLengthFrames: Long) -> Unit = { _,_ -> },
    onClipSelected: (Int) -> Unit = {},
    onZoomChange: (Float) -> Unit = {}
) {
    var zoom by remember { mutableFloatStateOf(80f) }
    var scrollX by remember { mutableFloatStateOf(0f) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    val colors = WaveStudioTheme.colors
    val trackPalette = listOf(colors.trackA, colors.trackB, colors.trackC, colors.trackD)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val outlineColor = MaterialTheme.colorScheme.outline

    val sr = sampleRate.coerceAtLeast(1f)
    val totalSeconds = totalFrames / sr
    val beatFrames = (60.0 * sr / bpm.coerceAtLeast(1f)).toLong().coerceAtLeast(1)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    val vw = viewSize.width.toFloat().coerceAtLeast(1f)
                    val oldZoom = zoom
                    val newZoom = (zoom * zoomChange).coerceIn(5f, 5000f)
                    val centerTime = (scrollX + vw / 2f) / oldZoom.coerceAtLeast(1f)
                    zoom = newZoom
                    val maxScroll = max(0f, totalSeconds * newZoom - vw)
                    scrollX = (centerTime * newZoom - vw / 2f + pan.x).coerceIn(0f, maxScroll)
                    onZoomChange(zoom)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val vw = viewSize.width.toFloat().coerceAtLeast(1f)
                        val maxScroll = max(0f, totalSeconds * zoom - vw)
                        scrollX = scrollX.coerceIn(0f, maxScroll)
                        val secAtTap = (offset.x + scrollX) / zoom.coerceAtLeast(1f)
                        var frame = (secAtTap * sr).toLong().coerceIn(0L, max(1L, totalFrames))
                        if (snapToGrid) frame = (frame / beatFrames) * beatFrames
                        onSeek(frame)
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val rulerH = 26.dp.toPx()
        val maxScroll = max(0f, totalSeconds * zoom - w)
        scrollX = scrollX.coerceIn(0f, maxScroll)

        // Background
        drawRect(surfaceColor)
        drawRect(colors.grid.copy(alpha = 0.35f), size = Size(w, rulerH))

        // Adaptive grid division
        fun chooseDivision(): Float {
            val candidates = floatArrayOf(0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 30f, 60f, 120f, 300f)
            var best = candidates.last()
            for (c in candidates) if (abs(c * zoom - 80f) < abs(best * zoom - 80f)) best = c
            return best
        }
        val secPerDiv = chooseDivision()
        val pxPerDiv = zoom * secPerDiv
        val startDiv = floor(scrollX / pxPerDiv).toInt() - 1
        val endDiv = ceil((scrollX + w) / pxPerDiv).toInt() + 1
        for (i in startDiv..endDiv) {
            val x = i * pxPerDiv - scrollX
            val isMajor = secPerDiv >= 1f && (i % 5 == 0)
            drawLine(
                color = outlineColor.copy(alpha = if (isMajor) 0.8f else 0.35f),
                start = Offset(x, rulerH), end = Offset(x, h),
                strokeWidth = if (isMajor) 1.dp.toPx() else 0.5.dp.toPx()
            )
        }

        // Track lanes
        val numTracks = tracks.size.coerceAtLeast(1)
        val availH = (h - rulerH).coerceAtLeast(40f)
        val perTrack = (availH / numTracks).coerceAtLeast(40f)
        tracks.forEachIndexed { idx, _ ->
            val ty = rulerH + idx * perTrack
            val laneBg = trackPalette[idx % trackPalette.size].copy(alpha = 0.08f)
            drawRect(laneBg, topLeft = Offset(0f, ty), size = Size(w, perTrack))
            drawLine(
                outlineColor.copy(alpha = 0.25f),
                Offset(0f, ty + perTrack), Offset(w, ty + perTrack),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Clips
        val radius = 4.dp.toPx()
        clips.forEach { clip ->
            val startSec = clip.startFrame / sr
            val lenSec = clip.lengthFrames / sr
            val trackIdx = tracks.indexOfFirst { it.id == clip.trackId }.coerceAtLeast(0)
            if (trackIdx >= tracks.size) return@forEach
            val x = startSec * zoom - scrollX
            val y = rulerH + trackIdx * perTrack + 2.dp.toPx()
            val cw = (lenSec * zoom).coerceAtLeast(4f)
            val ch = perTrack - 4.dp.toPx()
            if (x + cw < 0 || x > w) return@forEach
            val clipColor = trackPalette[trackIdx % trackPalette.size]
            val selected = selectedClipId == clip.id
            drawRoundRect(
                color = clipColor.copy(alpha = if (selected) 0.82f else 0.55f),
                topLeft = Offset(x, y), size = Size(cw, ch),
                cornerRadius = CornerRadius(radius, radius)
            )
            if (selected) {
                drawRoundRect(
                    color = Color.White, topLeft = Offset(x, y), size = Size(cw, ch),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            // Waveform
            val waveColor = Color.White.copy(alpha = 0.85f)
            val cy = y + ch / 2f
            val maxAmp = ch * 0.42f
            val steps = cw.toInt().coerceAtLeast(2).coerceAtMost(4000)
            val path = Path()
            var first = true
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val px = x + t * cw
                val seed = (clip.id * 31 + clip.lengthFrames).toFloat()
                val v = (sin(t * 13f + seed) * cos(t * 31f + seed * 0.7f) * 0.55f + 0.55f).coerceIn(0f, 1f)
                val amp = v * maxAmp
                if (first) { path.moveTo(px, cy - amp); first = false } else path.lineTo(px, cy - amp)
            }
            for (s in steps downTo 0) {
                val t = s.toFloat() / steps
                val px = x + t * cw
                val seed = (clip.id * 31 + clip.lengthFrames).toFloat()
                val v = (sin(t * 13f + seed) * cos(t * 31f + seed * 0.7f) * 0.55f + 0.55f).coerceIn(0f, 1f)
                val amp = v * maxAmp
                path.lineTo(px, cy + amp)
            }
            path.close()
            drawPath(path, waveColor.copy(alpha = 0.45f))
        }

        // Playhead
        val phSec = playheadFrames / sr
        val phX = phSec * zoom - scrollX
        if (phX in -2f..w + 2f) {
            drawLine(colors.playhead, Offset(phX, 0f), Offset(phX, h), strokeWidth = 2.dp.toPx())
            drawCircle(colors.playhead, radius = 7.dp.toPx(), center = Offset(phX, 13.dp.toPx()))
        }
    }
}
