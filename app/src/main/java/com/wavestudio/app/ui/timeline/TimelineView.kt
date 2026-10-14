package com.wavestudio.app.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavestudio.app.project.ClipDoc
import com.wavestudio.app.project.MarkerDoc
import com.wavestudio.app.project.TIMELINE_RATE
import com.wavestudio.app.project.TrackDoc
import com.wavestudio.app.ui.editor.PeakData
import com.wavestudio.app.ui.theme.WaveStudioTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Multitrack timeline. Everything drawn comes from the project document and the audio files:
 * clip positions/lengths/fades from [ClipDoc], waveforms from native peak data, bar/beat grid from the tempo.
 *
 * Gestures: tap = select clip / move playhead; drag a clip body = move (across tracks too);
 * drag a clip's left/right edge = trim; drag on the ruler = scrub; drag empty space = scroll; two fingers = zoom.
 */
@Composable
fun TimelineView(
    tracks: List<TrackDoc>,
    clips: List<ClipDoc>,
    peaks: Map<String, PeakData>,
    missingMedia: Set<String>,
    playheadFrames: Long,
    totalFrames: Long,
    bpm: Float,
    beatsPerBar: Int,
    loopEnabled: Boolean,
    loopStart: Long,
    loopEnd: Long,
    markers: List<MarkerDoc>,
    punchEnabled: Boolean,
    punchIn: Long,
    punchOut: Long,
    selectedClipId: Int?,
    recordingTrackId: Int?,
    zoom: Float,                       // pixels per second
    scrollYState: MutableFloatState,   // vertical scroll in px, hoisted so track headers can follow it
    modifier: Modifier = Modifier,
    onZoomChange: (Float) -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onSelectClip: (Int?) -> Unit = {},
    onMoveClip: (clipId: Int, newStartFrame: Long, newTrackId: Int?) -> Unit = { _, _, _ -> },
    onTrimStart: (clipId: Int, frame: Long) -> Unit = { _, _ -> },
    onTrimEnd: (clipId: Int, frame: Long) -> Unit = { _, _ -> }
) {
    var scrollX by remember { mutableFloatStateOf(0f) }

    val colors = WaveStudioTheme.colors
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val errorColor = MaterialTheme.colorScheme.error
    val markerColor = MaterialTheme.colorScheme.tertiary
    val palette = listOf(colors.trackA, colors.trackB, colors.trackC, colors.trackD)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = onSurface.copy(alpha = 0.7f), fontSize = 10.sp)
    val clipLabelStyle = TextStyle(color = Color.White, fontSize = 10.sp)

    val rate = TIMELINE_RATE.toFloat()
    val trackHeightDp = 84f

    // Latest values for the gesture coroutine (it is created once).
    val zoomS = rememberUpdatedState(zoom)
    val scrollY = scrollYState.floatValue
    val tracksS = rememberUpdatedState(tracks)
    val clipsS = rememberUpdatedState(clips)
    val totalS = rememberUpdatedState(totalFrames)
    val onZoomS = rememberUpdatedState(onZoomChange)
    val onSeekS = rememberUpdatedState(onSeek)
    val onSelectS = rememberUpdatedState(onSelectClip)
    val onMoveS = rememberUpdatedState(onMoveClip)
    val onTrimStartS = rememberUpdatedState(onTrimStart)
    val onTrimEndS = rememberUpdatedState(onTrimEnd)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val rulerH = 28.dp.toPx()
                val trackH = trackHeightDp.dp.toPx()
                val edge = 18.dp.toPx()
                fun frameAt(x: Float): Long = (((x + scrollX) / zoomS.value) * rate).toLong().coerceAtLeast(0L)
                fun trackIndexAt(y: Float): Int = floor((y - rulerH + scrollYState.floatValue) / trackH).toInt()
                fun clampScroll() {
                    val contentW = totalS.value / rate * zoomS.value
                    scrollX = scrollX.coerceIn(0f, max(0f, contentW - size.width * 0.5f))
                    val contentH = tracksS.value.size * trackH
                    scrollYState.floatValue = scrollYState.floatValue.coerceIn(0f, max(0f, contentH - (size.height - rulerH)))
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // ---- what was touched?
                    var kind = 0                      // 0 empty, 1 clip move, 2 trim start, 3 trim end, 4 ruler
                    var hitClip: ClipDoc? = null
                    var grab = 0L
                    if (down.position.y < rulerH) kind = 4
                    else {
                        val ti = trackIndexAt(down.position.y)
                        val track = tracksS.value.getOrNull(ti)
                        if (track != null) {
                            val f = frameAt(down.position.x)
                            val c = clipsS.value.lastOrNull { it.trackId == track.id && f >= it.startFrame && f < it.endFrame }
                            if (c != null) {
                                hitClip = c
                                val sx = (c.startFrame / rate * zoomS.value) - scrollX
                                val ex = (c.endFrame / rate * zoomS.value) - scrollX
                                val e = min(edge, (ex - sx) / 3f)
                                kind = when {
                                    down.position.x - sx < e -> 2
                                    ex - down.position.x < e -> 3
                                    else -> 1
                                }
                                grab = f - c.startFrame
                            }
                        }
                    }
                    var dragging = false
                    var multi = false
                    var moved = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            multi = true
                            val zc = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zc != 1f) {
                                val newZoom = (zoomS.value * zc).coerceIn(5f, 4000f)
                                val anchor = event.changes.map { it.position.x }.average().toFloat()
                                val secAtAnchor = (anchor + scrollX) / zoomS.value
                                onZoomS.value(newZoom)
                                scrollX = secAtAnchor * newZoom - anchor
                            }
                            scrollX -= pan.x; scrollYState.floatValue -= pan.y
                            clampScroll()
                            event.changes.forEach { it.consume() }
                        } else if (!multi) {
                            val c = event.changes.first()
                            if (!dragging) {
                                moved += c.positionChange()
                                if (moved.getDistance() > slop) dragging = true
                            }
                            if (dragging) {
                                val p = c.position
                                when (kind) {
                                    4 -> onSeekS.value(frameAt(p.x))
                                    1 -> {
                                        val h = hitClip!!
                                        val ti = trackIndexAt(p.y).coerceIn(0, max(0, tracksS.value.size - 1))
                                        val targetTrack = tracksS.value.getOrNull(ti)?.id
                                        onMoveS.value(h.id, frameAt(p.x) - grab, targetTrack?.takeIf { it != h.trackId })
                                    }
                                    2 -> onTrimStartS.value(hitClip!!.id, frameAt(p.x))
                                    3 -> onTrimEndS.value(hitClip!!.id, frameAt(p.x))
                                    else -> { scrollX -= c.positionChange().x; scrollYState.floatValue -= c.positionChange().y; clampScroll() }
                                }
                                c.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!dragging && !multi) {
                        when (kind) {
                            4, 0 -> { if (kind == 0) onSelectS.value(null); onSeekS.value(frameAt(down.position.x)) }
                            else -> onSelectS.value(hitClip!!.id)
                        }
                    } else if (dragging && hitClip != null && kind in 1..3) {
                        onSelectS.value(hitClip.id)
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val rulerH = 28.dp.toPx()
        val trackH = trackHeightDp.dp.toPx()
        drawRect(surfaceColor)

        // ---- bar / beat grid + ruler
        val secPerBeat = 60f / bpm.coerceAtLeast(1f)
        val beatPx = secPerBeat * zoom
        val barPx = beatPx * beatsPerBar
        val firstBeat = max(0, floor(scrollX / beatPx).toInt())
        val lastBeat = ceil((scrollX + w) / beatPx).toInt()
        val labelEvery = max(1, ceil(56f / barPx).toInt())
        drawRect(colors.grid.copy(alpha = 0.5f), size = Size(w, rulerH))
        if (beatPx > 6f) for (b in firstBeat..lastBeat) {
            val x = b * beatPx - scrollX
            val bar = b % beatsPerBar == 0
            if (!bar && beatPx < 14f) continue
            drawLine(colors.grid.copy(alpha = if (bar) 0.7f else 0.3f), Offset(x, if (bar) 0f else rulerH * 0.6f), Offset(x, h), strokeWidth = if (bar) 1.dp.toPx() else 0.5.dp.toPx())
            if (bar) {
                val barNo = b / beatsPerBar
                if (barNo % labelEvery == 0) drawText(measurer, "${barNo + 1}", Offset(x + 3.dp.toPx(), 2.dp.toPx()), style = labelStyle)
            }
        } else {
            // zoomed far out: one line per second block
            val step = 10f * ceil(60f / (10f * zoom)).coerceAtLeast(1f)
            var s = floor(scrollX / zoom / step) * step
            while (s * zoom - scrollX < w) {
                val x = s * zoom - scrollX
                drawLine(colors.grid.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, h), strokeWidth = 1.dp.toPx())
                drawText(measurer, "${(s / 60).toInt()}:%02d".format((s % 60).toInt()), Offset(x + 3.dp.toPx(), 2.dp.toPx()), style = labelStyle)
                s += step
            }
        }

        // ---- loop region
        if (loopEnd > loopStart) {
            val lx = loopStart / rate * zoom - scrollX
            val rx = loopEnd / rate * zoom - scrollX
            drawRect(
                (if (loopEnabled) colors.playhead else onSurface).copy(alpha = if (loopEnabled) 0.18f else 0.06f),
                topLeft = Offset(lx, 0f), size = Size(rx - lx, rulerH)
            )
            if (loopEnabled) {
                drawLine(colors.playhead, Offset(lx, 0f), Offset(lx, h), strokeWidth = 1.dp.toPx())
                drawLine(colors.playhead, Offset(rx, 0f), Offset(rx, h), strokeWidth = 1.dp.toPx())
            }
        }

        // ---- punch range (only this part of a take is written while recording)
        if (punchEnabled && punchOut > punchIn) {
            val px0 = punchIn / rate * zoom - scrollX
            val px1 = punchOut / rate * zoom - scrollX
            drawRect(errorColor.copy(alpha = 0.10f), topLeft = Offset(px0, rulerH), size = Size(px1 - px0, h - rulerH))
            drawRect(errorColor.copy(alpha = 0.45f), topLeft = Offset(px0, 0f), size = Size(px1 - px0, rulerH * 0.35f))
            drawLine(errorColor, Offset(px0, 0f), Offset(px0, h), strokeWidth = 1.5.dp.toPx())
            drawLine(errorColor, Offset(px1, 0f), Offset(px1, h), strokeWidth = 1.5.dp.toPx())
        }

        // ---- markers
        markers.forEach { m ->
            val x = m.frame / rate * zoom - scrollX
            if (x < -80f || x > w) return@forEach
            drawLine(markerColor.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, h), strokeWidth = 1.dp.toPx())
            drawText(measurer, m.name, Offset(x + 3.dp.toPx(), rulerH * 0.45f), style = labelStyle.copy(color = markerColor), maxLines = 1)
        }

        // ---- track lanes
        tracks.forEachIndexed { idx, t ->
            val ty = rulerH + idx * trackH - scrollY
            if (ty + trackH < rulerH || ty > h) return@forEachIndexed
            val recording = recordingTrackId == t.id
            drawRect(
                if (recording) errorColor.copy(alpha = 0.12f) else palette[idx % palette.size].copy(alpha = 0.08f),
                topLeft = Offset(0f, ty), size = Size(w, trackH)
            )
            drawLine(colors.grid, Offset(0f, ty + trackH), Offset(w, ty + trackH), strokeWidth = 1.dp.toPx())
        }

        // ---- clips
        val trackIndex = tracks.withIndex().associate { it.value.id to it.index }
        clips.forEach { clip ->
            val ti = trackIndex[clip.trackId] ?: return@forEach
            val x = clip.startFrame / rate * zoom - scrollX
            val cw = clip.lengthFrames / rate * zoom
            val y = rulerH + ti * trackH - scrollY + 2.dp.toPx()
            val ch = trackH - 4.dp.toPx()
            if (x + cw < 0 || x > w || y + ch < rulerH || y > h) return@forEach
            val base = palette[ti % palette.size]
            val selected = selectedClipId == clip.id
            val missing = clip.media in missingMedia
            val corner = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            drawRoundRect(
                color = (if (missing) errorColor else base).copy(alpha = if (selected) 0.75f else 0.5f),
                topLeft = Offset(x, y), size = Size(max(cw, 3f), ch), cornerRadius = corner
            )

            // Waveform from real audio data
            val pd = peaks[clip.media]
            if (pd != null && pd.bins > 0) {
                val x0 = max(x, 0f); val x1 = min(x + cw, w)
                val mid = y + ch / 2f; val amp = ch * 0.45f
                val gain = Math.pow(10.0, clip.gainDb / 20.0).toFloat()
                var px = floor(x0)
                while (px < x1) {
                    val srcSecA = (clip.offsetFrames / rate) + (px - x) / zoom
                    val srcSecB = (clip.offsetFrames / rate) + (px + 1.5f - x) / zoom
                    var b0 = (srcSecA * pd.binsPerSecond).toInt().coerceIn(0, pd.bins - 1)
                    var b1 = (srcSecB * pd.binsPerSecond).toInt().coerceIn(b0, pd.bins - 1)
                    if (b1 - b0 > 64) { b0 = (b0 + b1) / 2 - 32; b1 = b0 + 64 }
                    var mn = 0f; var mx = 0f
                    for (b in b0..b1) { mn = min(mn, pd.minMax[2 * b]); mx = max(mx, pd.minMax[2 * b + 1]) }
                    // fade envelope so the picture matches what you hear
                    val posInClip = (px - x) / zoom * rate
                    var env = 1f
                    if (clip.fadeInFrames > 0 && posInClip < clip.fadeInFrames) env = posInClip / clip.fadeInFrames
                    val fromEnd = clip.lengthFrames - posInClip
                    if (clip.fadeOutFrames > 0 && fromEnd < clip.fadeOutFrames) env = min(env, fromEnd / clip.fadeOutFrames)
                    val top = (mid - (mx * gain * env).coerceIn(-1f, 1f) * amp)
                    val bot = (mid - (mn * gain * env).coerceIn(-1f, 1f) * amp)
                    drawLine(Color.White.copy(alpha = 0.85f), Offset(px + 0.5f, top), Offset(px + 0.5f, max(bot, top + 1f)), strokeWidth = 1.2f)
                    px += 1f
                }
            }
            if (selected) {
                drawRoundRect(Color.White, topLeft = Offset(x, y), size = Size(max(cw, 3f), ch), cornerRadius = corner, style = Stroke(width = 2.dp.toPx()))
                // trim handles
                drawRect(Color.White, topLeft = Offset(x, y), size = Size(4.dp.toPx(), ch))
                drawRect(Color.White, topLeft = Offset(x + cw - 4.dp.toPx(), y), size = Size(4.dp.toPx(), ch))
            }
            // fade ramps
            if (clip.fadeInFrames > 0) {
                val fw = clip.fadeInFrames / rate * zoom
                drawLine(Color.White.copy(alpha = 0.8f), Offset(x, y + ch), Offset(x + fw, y), strokeWidth = 1.dp.toPx())
            }
            if (clip.fadeOutFrames > 0) {
                val fw = clip.fadeOutFrames / rate * zoom
                drawLine(Color.White.copy(alpha = 0.8f), Offset(x + cw - fw, y), Offset(x + cw, y + ch), strokeWidth = 1.dp.toPx())
            }
            if (cw > 40f) {
                val label = (if (missing) "MISSING: " else "") + clip.name
                drawText(measurer, label, Offset(max(x, 0f) + 6.dp.toPx(), y + 2.dp.toPx()), style = clipLabelStyle, maxLines = 1)
            }
        }

        // ---- playhead
        val phX = playheadFrames / rate * zoom - scrollX
        if (phX in -10f..(w + 10f)) {
            drawLine(colors.playhead, Offset(phX, 0f), Offset(phX, h), strokeWidth = 2.dp.toPx())
            drawCircle(colors.playhead, radius = 6.dp.toPx(), center = Offset(phX, 12.dp.toPx()))
        }
    }
}

/** Height of one track lane in dp (the track header list uses the same value so rows line up). */
const val TIMELINE_TRACK_HEIGHT_DP = 84
const val TIMELINE_RULER_HEIGHT_DP = 28
