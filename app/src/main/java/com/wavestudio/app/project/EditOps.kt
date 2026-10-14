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
    /** On an aux bus the effect must be 100 % wet (the dry signal is already in the mix), so these start fully wet. */
    private fun busDefaults(type: String): Map<String, Float> = when (type) {
        "reverb" -> mapOf("wet" to 1f, "dry" to 0f)
        "delay", "chorus", "flanger", "phaser", "distortion", "bitcrusher" -> mapOf("mix" to 1f)
        else -> emptyMap()
    }

    /** [trackId]: >0 track, 0 master, -1..-MAX_BUSES aux bus (-(index+1)). Returns the new effect id, or -1 for an unknown target. */
    fun addEffect(d: ProjectDoc, trackId: Int, type: String): Pair<ProjectDoc, Int> {
        val id = d.nextId
        if (trackId < 0) {
            val bus = d.bus(-trackId - 1) ?: return d to -1
            val e = EffectDoc(id = id, type = type, params = busDefaults(type))
            return updateBus(d.copy(nextId = id + 1), bus.index) { it.copy(effects = it.effects + e) } to id
        }
        val e = EffectDoc(id = id, type = type)
        return if (trackId == 0) d.copy(masterEffects = d.masterEffects + e, nextId = id + 1) to id
        else if (d.track(trackId) == null) d to -1
        else updateTrack(d.copy(nextId = id + 1), trackId) { it.copy(effects = it.effects + e) } to id
    }

    private fun mapEffect(d: ProjectDoc, effectId: Int, f: (EffectDoc) -> EffectDoc): ProjectDoc {
        if (d.masterEffects.any { it.id == effectId })
            return d.copy(masterEffects = d.masterEffects.map { if (it.id == effectId) f(it) else it })
        d.buses.firstOrNull { bu -> bu.effects.any { it.id == effectId } }?.let { bu ->
            return updateBus(d, bu.index) { b -> b.copy(effects = b.effects.map { if (it.id == effectId) f(it) else it }) }
        }
        val t = d.tracks.firstOrNull { tr -> tr.effects.any { it.id == effectId } } ?: return d
        return updateTrack(d, t.id) { tr -> tr.copy(effects = tr.effects.map { if (it.id == effectId) f(it) else it }) }
    }
    fun removeEffect(d: ProjectDoc, effectId: Int): ProjectDoc {
        if (d.masterEffects.any { it.id == effectId }) return d.copy(masterEffects = d.masterEffects.filter { it.id != effectId })
        d.buses.firstOrNull { bu -> bu.effects.any { it.id == effectId } }?.let { bu ->
            return updateBus(d, bu.index) { b -> b.copy(effects = b.effects.filter { e -> e.id != effectId }) }
        }
        val t = d.tracks.firstOrNull { tr -> tr.effects.any { it.id == effectId } } ?: return d
        return updateTrack(d, t.id) { it.copy(effects = it.effects.filter { e -> e.id != effectId }) }
    }
    fun setEffectParam(d: ProjectDoc, effectId: Int, paramId: String, value: Float): ProjectDoc =
        mapEffect(d, effectId) { it.copy(params = it.params + (paramId to value)) }
    fun setEffectBypass(d: ProjectDoc, effectId: Int, bypass: Boolean): ProjectDoc =
        mapEffect(d, effectId) { it.copy(bypass = bypass) }

    // ------------------------------------------------------------------ aux buses and sends
    fun updateBus(d: ProjectDoc, index: Int, f: (BusDoc) -> BusDoc): ProjectDoc {
        val b = d.bus(index) ?: return d
        val n = f(b)
        if (n == b) return d
        return d.copy(buses = d.buses.map { if (it.index == index) n else it })
    }
    /** Adds a bus in the lowest free slot. Returns its index, or -1 when all [MAX_BUSES] are in use. */
    fun addBus(d: ProjectDoc, name: String? = null): Pair<ProjectDoc, Int> {
        val index = (0 until MAX_BUSES).firstOrNull { d.bus(it) == null } ?: return d to -1
        val n = name?.trim()?.takeIf { it.isNotEmpty() }?.take(24) ?: "Aux ${index + 1}"
        return d.copy(buses = (d.buses + BusDoc(index, n)).sortedBy { it.index }) to index
    }
    /** Removes a bus, its effects, and every track's send to it. */
    fun removeBus(d: ProjectDoc, index: Int): ProjectDoc {
        if (d.bus(index) == null) return d
        return d.copy(
            buses = d.buses.filter { it.index != index },
            tracks = d.tracks.map { if (index in it.sends) it.copy(sends = it.sends - index) else it }
        )
    }
    fun renameBus(d: ProjectDoc, index: Int, name: String): ProjectDoc {
        val n = name.trim().take(24)
        return if (n.isEmpty()) d else updateBus(d, index) { it.copy(name = n) }
    }
    fun setBusLevel(d: ProjectDoc, index: Int, level: Float): ProjectDoc =
        updateBus(d, index) { it.copy(level = (if (level.isNaN()) 1f else level).coerceIn(0f, 2f)) }
    /** Sets a track's send level into [bus]; 0 removes the send. Unknown track/bus: unchanged. */
    fun setTrackSend(d: ProjectDoc, trackId: Int, bus: Int, level: Float): ProjectDoc {
        if (d.bus(bus) == null) return d
        val lv = (if (level.isNaN()) 0f else level).coerceIn(0f, 2f)
        return updateTrack(d, trackId) { t ->
            if (lv <= 0f) t.copy(sends = t.sends - bus) else t.copy(sends = t.sends + (bus to lv))
        }
    }

    // ------------------------------------------------------------------ markers
    fun addMarker(d: ProjectDoc, frame: Long, name: String? = null): Pair<ProjectDoc, Int> {
        if (frame < 0) return d to -1
        val id = d.nextId
        val n = name?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: "Marker ${d.markers.size + 1}"
        return d.copy(markers = (d.markers + MarkerDoc(id, frame, n)).sortedBy { it.frame }, nextId = id + 1) to id
    }
    fun removeMarker(d: ProjectDoc, id: Int): ProjectDoc =
        if (d.markers.none { it.id == id }) d else d.copy(markers = d.markers.filter { it.id != id })
    fun renameMarker(d: ProjectDoc, id: Int, name: String): ProjectDoc {
        val n = name.trim().take(40)
        if (n.isEmpty() || d.markers.none { it.id == id && it.name != n }) return d
        return d.copy(markers = d.markers.map { if (it.id == id) it.copy(name = n) else it })
    }
    fun moveMarker(d: ProjectDoc, id: Int, frame: Long): ProjectDoc {
        if (frame < 0 || d.markers.none { it.id == id && it.frame != frame }) return d
        return d.copy(markers = d.markers.map { if (it.id == id) it.copy(frame = frame) else it }.sortedBy { it.frame })
    }
    /** First marker strictly after [frame] (with a small tolerance so repeated "next" always advances). */
    fun nextMarker(d: ProjectDoc, frame: Long, tolerance: Long = 480): MarkerDoc? =
        d.markers.filter { it.frame > frame + tolerance }.minByOrNull { it.frame }
    /** Last marker before [frame]; the tolerance keeps "previous" from sticking to the marker the playhead is already on. */
    fun prevMarker(d: ProjectDoc, frame: Long, tolerance: Long = TIMELINE_RATE / 4L): MarkerDoc? =
        d.markers.filter { it.frame < frame - tolerance }.maxByOrNull { it.frame }

    // ------------------------------------------------------------------ punch in/out
    /** Sets the punch range. An empty or inverted range is rejected (the document is returned unchanged). */
    fun setPunchRange(d: ProjectDoc, inFrame: Long, outFrame: Long): ProjectDoc {
        if (inFrame < 0 || outFrame - inFrame < MIN_CLIP) return d
        if (d.punchIn == inFrame && d.punchOut == outFrame) return d
        return d.copy(punchIn = inFrame, punchOut = outFrame)
    }
    fun setPunchEnabled(d: ProjectDoc, enabled: Boolean): ProjectDoc {
        if (enabled && d.punchOut - d.punchIn < MIN_CLIP) return d      // no valid range yet
        return if (d.punchEnabled == enabled) d else d.copy(punchEnabled = enabled)
    }

    // ------------------------------------------------------------------ normalize
    /** Gain (dB) that brings a clip whose unity-gain peak is [peakLinear] to [targetDb] dBFS, or null for silence. */
    fun normalizeGainDb(peakLinear: Float, targetDb: Float = -1f): Float? {
        if (!(peakLinear > 1e-5f)) return null
        return (targetDb - 20f * Math.log10(peakLinear.toDouble()).toFloat()).coerceIn(-60f, 24f)
    }

    // ------------------------------------------------------------------ grid / snapping
    fun snap(frame: Long, gridFrames: Double, enabled: Boolean = true): Long =
        if (!enabled || gridFrames < 1.0) frame else (Math.round(frame / gridFrames) * gridFrames).toLong()

    const val MIN_CLIP = 64L
}

/** Snapshot-based undo/redo over immutable documents. Continuous gestures (fader drags) coalesce into one step. */
class History<T>(private val limit: Int = 200, private val clock: () -> Long = System::currentTimeMillis) {
    private class Entry<T>(val label: String, val before: T, var after: T, val key: String?, var at: Long)
    private val undoStack = ArrayDeque<Entry<T>>()
    private val redoStack = ArrayDeque<Entry<T>>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val undoLabel get() = undoStack.lastOrNull()?.label
    val redoLabel get() = redoStack.lastOrNull()?.label

    /** [coalesceKey]: consecutive records with the same key within [windowMs] merge into a single undo step. */
    fun record(label: String, before: T, after: T, coalesceKey: String? = null, windowMs: Long = 800) {
        if (before === after) return
        val now = clock()
        val last = undoStack.lastOrNull()
        if (coalesceKey != null && last != null && last.key == coalesceKey && now - last.at <= windowMs && redoStack.isEmpty()) {
            last.after = after; last.at = now
            return
        }
        undoStack.addLast(Entry(label, before, after, coalesceKey, now))
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }
    fun undo(): T? { val e = undoStack.removeLastOrNull() ?: return null; redoStack.addLast(e); return e.before }
    fun redo(): T? { val e = redoStack.removeLastOrNull() ?: return null; undoStack.addLast(e); return e.after }
    fun clear() { undoStack.clear(); redoStack.clear() }
}
