package com.wavestudio.app.project

/**
 * Pure editing operations on the immutable [ProjectDoc]. Each returns a new document (or the
 * same instance when the request is invalid/no-op) so the caller can compare identity to know
 * whether anything changed. No Android or engine dependencies: fully unit-testable.
 */
object EditOps {

    private val palette = longArrayOf(
        0xFF4A90E2, 0xFFE27A4A, 0xFF9A4AE2, 0xFF4AE299, 0xFFE2D64A, 0xFFE24A88, 0xFF4AD7E2, 0xFFFF8A3D
    )

    // ------------------------------------------------------------------ tracks
    fun addTrack(d: ProjectDoc, name: String? = null): Pair<ProjectDoc, Int> {
        val id = d.nextId
        val t = TrackDoc(id = id, name = name ?: "Track ${d.tracks.size + 1}", color = palette[d.tracks.size % palette.size])
        return d.copy(tracks = d.tracks + t, nextId = id + 1) to id
    }

    fun removeTrack(d: ProjectDoc, id: Int): ProjectDoc {
        if (d.track(id) == null) return d
        return d.copy(tracks = d.tracks.filter { it.id != id }, clips = d.clips.filter { it.trackId != id })
    }

    fun updateTrack(d: ProjectDoc, id: Int, f: (TrackDoc) -> TrackDoc): ProjectDoc {
        val t = d.track(id) ?: return d
        val n = f(t)
        if (n == t) return d
        return d.copy(tracks = d.tracks.map { if (it.id == id) n else it })
    }

    // ------------------------------------------------------------------ clips
    fun addClip(d: ProjectDoc, trackId: Int, media: String, name: String, startFrame: Long, lengthFrames: Long, sourceFrames: Long = lengthFrames): Pair<ProjectDoc, Int> {
        if (d.track(trackId) == null || lengthFrames <= 0) return d to -1
        val id = d.nextId
        val c = ClipDoc(id = id, trackId = trackId, media = media, name = name, startFrame = maxOf(0, startFrame),
            lengthFrames = lengthFrames, sourceFrames = maxOf(sourceFrames, lengthFrames))
        return d.copy(clips = d.clips + c, nextId = id + 1) to id
    }

    fun updateClip(d: ProjectDoc, id: Int, f: (ClipDoc) -> ClipDoc): ProjectDoc {
        val c = d.clip(id) ?: return d
        val n = f(c)
        if (n == c) return d
        return d.copy(clips = d.clips.map { if (it.id == id) n else it })
    }

    fun deleteClip(d: ProjectDoc, id: Int): ProjectDoc =
        if (d.clip(id) == null) d else d.copy(clips = d.clips.filter { it.id != id })

    fun moveClip(d: ProjectDoc, id: Int, newStart: Long, newTrackId: Int? = null): ProjectDoc =
        updateClip(d, id) { c ->
            val track = if (newTrackId != null && d.track(newTrackId) != null) newTrackId else c.trackId
            c.copy(startFrame = maxOf(0, newStart), trackId = track)
        }

    /** Splits at [atFrame] (timeline). Both halves keep the same media; the second gets a new id. Returns (doc, newClipId or -1). */
    fun splitClip(d: ProjectDoc, id: Int, atFrame: Long): Pair<ProjectDoc, Int> {
        val c = d.clip(id) ?: return d to -1
        if (atFrame <= c.startFrame || atFrame >= c.endFrame) return d to -1
        val leftLen = atFrame - c.startFrame
        val newId = d.nextId
        val left = c.copy(lengthFrames = leftLen, fadeOutFrames = 0)
        val right = c.copy(
            id = newId, startFrame = atFrame, offsetFrames = c.offsetFrames + leftLen,
            lengthFrames = c.lengthFrames - leftLen, fadeInFrames = 0
        )
        val clips = d.clips.flatMap { if (it.id == id) listOf(left, right) else listOf(it) }
        return d.copy(clips = clips, nextId = newId + 1) to newId
    }

    /** Moves the clip's left edge and/or right edge (timeline frames), bounded by the source media. */
    fun trimClip(d: ProjectDoc, id: Int, newStart: Long = Long.MIN_VALUE, newEnd: Long = Long.MIN_VALUE): ProjectDoc =
        updateClip(d, id) { c ->
            var start = c.startFrame; var off = c.offsetFrames; var end = c.endFrame
            if (newStart != Long.MIN_VALUE) {
                // Cannot reveal media before the source start, nor cross the right edge.
                val minStart = c.startFrame - c.offsetFrames
                val s = newStart.coerceIn(maxOf(0L, minStart), end - MIN_CLIP)
                off += s - start; start = s
            }
            if (newEnd != Long.MIN_VALUE) {
                val maxEnd = start - off + c.sourceFrames
                end = newEnd.coerceIn(start + MIN_CLIP, maxEnd)
            }
            c.copy(startFrame = start, offsetFrames = off, lengthFrames = end - start,
                fadeInFrames = c.fadeInFrames.coerceAtMost(end - start), fadeOutFrames = c.fadeOutFrames.coerceAtMost(end - start))
        }

    /** Copies the clip directly after itself on the same track. Returns (doc, new id). */
    fun duplicateClip(d: ProjectDoc, id: Int): Pair<ProjectDoc, Int> {
        val c = d.clip(id) ?: return d to -1
        val newId = d.nextId
        return d.copy(clips = d.clips + c.copy(id = newId, startFrame = c.endFrame), nextId = newId + 1) to newId
    }

    fun setClipFades(d: ProjectDoc, id: Int, fadeIn: Long, fadeOut: Long): ProjectDoc = updateClip(d, id) { c ->
        val fi = fadeIn.coerceIn(0, c.lengthFrames); val fo = fadeOut.coerceIn(0, c.lengthFrames - fi)
        c.copy(fadeInFrames = fi, fadeOutFrames = fo)
    }

    fun setClipGain(d: ProjectDoc, id: Int, gainDb: Float): ProjectDoc =
        updateClip(d, id) { it.copy(gainDb = gainDb.coerceIn(-60f, 24f)) }

    // ------------------------------------------------------------------ effects
    fun addEffect(d: ProjectDoc, trackId: Int, type: String): Pair<ProjectDoc, Int> {
        val id = d.nextId
        val e = EffectDoc(id = id, type = type)
        return if (trackId == 0) d.copy(masterEffects = d.masterEffects + e, nextId = id + 1) to id
        else if (d.track(trackId) == null) d to -1
        else updateTrack(d.copy(nextId = id + 1), trackId) { it.copy(effects = it.effects + e) } to id
    }

    private fun mapEffect(d: ProjectDoc, effectId: Int, f: (EffectDoc) -> EffectDoc): ProjectDoc {
        if (d.masterEffects.any { it.id == effectId })
            return d.copy(masterEffects = d.masterEffects.map { if (it.id == effectId) f(it) else it })
        val t = d.tracks.firstOrNull { tr -> tr.effects.any { it.id == effectId } } ?: return d
        return updateTrack(d, t.id) { tr -> tr.copy(effects = tr.effects.map { if (it.id == effectId) f(it) else it }) }
    }
    fun removeEffect(d: ProjectDoc, effectId: Int): ProjectDoc {
        if (d.masterEffects.any { it.id == effectId }) return d.copy(masterEffects = d.masterEffects.filter { it.id != effectId })
        val t = d.tracks.firstOrNull { tr -> tr.effects.any { it.id == effectId } } ?: return d
        return updateTrack(d, t.id) { it.copy(effects = it.effects.filter { e -> e.id != effectId }) }
    }
    fun setEffectParam(d: ProjectDoc, effectId: Int, paramId: String, value: Float): ProjectDoc =
        mapEffect(d, effectId) { it.copy(params = it.params + (paramId to value)) }
    fun setEffectBypass(d: ProjectDoc, effectId: Int, bypass: Boolean): ProjectDoc =
        mapEffect(d, effectId) { it.copy(bypass = bypass) }

    // ------------------------------------------------------------------ grid / snapping
    fun snap(frame: Long, gridFrames: Double, enabled: Boolean = true): Long =
        if (!enabled || gridFrames < 1.0) frame else (Math.round(frame / gridFrames) * gridFrames).toLong()

    const val MIN_CLIP = 64L
}

/** Snapshot-based undo/redo over immutable documents. */
class History<T>(private val limit: Int = 200) {
    private data class Entry<T>(val label: String, val before: T, val after: T)
    private val undoStack = ArrayDeque<Entry<T>>()
    private val redoStack = ArrayDeque<Entry<T>>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val undoLabel get() = undoStack.lastOrNull()?.label
    val redoLabel get() = redoStack.lastOrNull()?.label

    fun record(label: String, before: T, after: T) {
        if (before === after) return
        undoStack.addLast(Entry(label, before, after))
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }
    fun undo(): T? { val e = undoStack.removeLastOrNull() ?: return null; redoStack.addLast(e); return e.before }
    fun redo(): T? { val e = redoStack.removeLastOrNull() ?: return null; undoStack.addLast(e); return e.after }
    fun clear() { undoStack.clear(); redoStack.clear() }
}
