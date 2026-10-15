package com.wavestudio.app.project

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure timeline geometry (pixels per second, scroll, grid). Everything here is total: NaN / infinite / zero inputs
 * produce a usable value instead of an exception or an endless loop, because these numbers come straight from
 * touch gestures and a bad value used to be able to reach drawing code.
 */
object TimelineMath {
    const val MIN_ZOOM = 2f            // px per second (whole project visible)
    const val MAX_ZOOM = 6000f         // px per second (~160 µs per px at 48 kHz: sample-level detail)

    fun sane(v: Float, fallback: Float): Float = if (v.isNaN() || v.isInfinite()) fallback else v

    fun clampZoom(z: Float): Float = if (z == Float.POSITIVE_INFINITY) MAX_ZOOM else sane(z, 100f).coerceIn(MIN_ZOOM, MAX_ZOOM)

    fun contentWidth(totalFrames: Long, zoom: Float, rate: Float = TIMELINE_RATE.toFloat()): Float =
        max(0f, totalFrames.coerceAtLeast(0L) / rate * clampZoom(zoom))

    /** Largest legal horizontal scroll: the end of the project may reach the middle of the view, but never beyond. */
    fun maxScroll(totalFrames: Long, zoom: Float, viewWidth: Float): Float =
        max(0f, contentWidth(totalFrames, zoom) - max(0f, sane(viewWidth, 0f)) * 0.5f)

    fun clampScroll(scroll: Float, totalFrames: Long, zoom: Float, viewWidth: Float): Float =
        sane(scroll, 0f).coerceIn(0f, maxScroll(totalFrames, zoom, viewWidth))

    fun clampScrollY(scroll: Float, trackCount: Int, trackHeightPx: Float, viewHeightPx: Float): Float {
        val over = max(0f, trackCount * trackHeightPx - max(0f, viewHeightPx))
        return sane(scroll, 0f).coerceIn(0f, over)
    }

    /** New (zoom, scroll) after scaling by [factor] while keeping the time under [anchorX] (view pixels) fixed. */
    fun zoomAround(zoom: Float, scroll: Float, anchorX: Float, factor: Float, totalFrames: Long, viewWidth: Float): Pair<Float, Float> {
        val z0 = clampZoom(zoom)
        val f = sane(factor, 1f).let { if (it <= 0f) 1f else it }
        val z1 = clampZoom(z0 * f)
        val a = sane(anchorX, 0f).coerceIn(0f, max(0f, sane(viewWidth, 0f)))
        val sec = (sane(scroll, 0f) + a) / z0
        return z1 to clampScroll(sec * z1 - a, totalFrames, z1, viewWidth)
    }

    /** Zoom (px/s) that fits [totalFrames] (plus a little room) into [viewWidth]. */
    fun fitZoom(totalFrames: Long, viewWidth: Float): Float {
        val sec = max(1f, totalFrames.coerceAtLeast(0L) / TIMELINE_RATE.toFloat())
        return clampZoom(max(0f, sane(viewWidth, 0f)) * 0.9f / sec)
    }

    fun frameAt(x: Float, scroll: Float, zoom: Float, rate: Float = TIMELINE_RATE.toFloat()): Long {
        val f = (sane(x, 0f) + sane(scroll, 0f)) / clampZoom(zoom) * rate
        return if (f <= 0f) 0L else min(f, 9.0e15f).toLong()
    }

    /** First and last beat index that can be visible; the span is bounded so a bad input cannot create a huge loop. */
    fun visibleBeats(scroll: Float, viewWidth: Float, beatPx: Float, maxCount: Int = 4000): IntRange {
        if (!(beatPx > 0f) || beatPx.isInfinite()) return IntRange.EMPTY
        val first = max(0, floor(sane(scroll, 0f) / beatPx).toInt())
        val last = ceil((sane(scroll, 0f) + max(0f, viewWidth)) / beatPx).toInt()
        return first..min(last, first + maxCount)
    }

    /** Seconds between ruler labels when the beat grid is too dense: a "nice" step giving >= [minPx] pixels per label. */
    fun rulerStepSeconds(zoom: Float, minPx: Float = 70f): Float {
        val steps = floatArrayOf(0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f, 600f, 1800f, 3600f)
        val z = clampZoom(zoom)
        return steps.firstOrNull { it * z >= minPx } ?: steps.last()
    }

    fun timeLabel(seconds: Float): String {
        val s = max(0f, sane(seconds, 0f))
        val m = (s / 60f).toInt()
        val rem = s - m * 60f
        return if (rem == floor(rem)) "$m:%02d".format(rem.toInt()) else "$m:%04.1f".format(rem)
    }

    /** Clamp a drawn horizontal span to a margin around the view so huge rectangles are never handed to the renderer. */
    fun clipSpan(x0: Float, x1: Float, viewWidth: Float, margin: Float = 24f): Pair<Float, Float> =
        max(x0, -margin) to min(x1, viewWidth + margin)

    fun approxEquals(a: Float, b: Float, eps: Float = 1e-3f) = abs(a - b) <= eps
}
