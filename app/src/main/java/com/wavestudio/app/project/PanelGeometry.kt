package com.wavestudio.app.project

import kotlin.math.max
import kotlin.math.min

/** Pure geometry of a floating panel inside its parent: keeps it fully on screen and within size limits (all in pixels). */
object PanelGeometry {
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float)

    fun clamp(r: Rect, parentW: Float, parentH: Float, minW: Float, minH: Float): Rect {
        if (parentW <= 0f || parentH <= 0f || r.x.isNaN() || r.y.isNaN() || r.w.isNaN() || r.h.isNaN()) return Rect(0f, 0f, max(0f, minW), max(0f, minH))
        val lo = min(minW, parentW); val loH = min(minH, parentH)
        val w = r.w.coerceIn(lo, max(lo, parentW))
        val h = r.h.coerceIn(loH, max(loH, parentH))
        return Rect(r.x.coerceIn(0f, parentW - w), r.y.coerceIn(0f, parentH - h), w, h)
    }

    fun move(r: Rect, dx: Float, dy: Float, pw: Float, ph: Float, minW: Float, minH: Float) =
        clamp(r.copy(x = r.x + dx, y = r.y + dy), pw, ph, minW, minH)

    /** Resizes from the bottom-right corner: the top-left corner stays put and the panel cannot grow past the parent's edge. */
    fun resize(r: Rect, dw: Float, dh: Float, pw: Float, ph: Float, minW: Float, minH: Float): Rect {
        val w = (r.w + dw).coerceIn(minW, max(minW, pw - r.x))
        val h = (r.h + dh).coerceIn(minH, max(minH, ph - r.y))
        return clamp(r.copy(w = w, h = h), pw, ph, minW, minH)
    }
}
