package com.wavestudio.app.project

data class NativeParam(val id: String, val name: String, val unit: String, val min: Float, val max: Float, val def: Float, val value: Float)
data class NativeEffectInfo(val id: Int, val trackId: Int, val type: String, val name: String, val bypass: Boolean, val params: List<NativeParam>) {
    companion object {
        fun parse(json: String): NativeEffectInfo? = try {
            val m = Json.parseObject(json)
            if (m.isEmpty()) null else NativeEffectInfo(
                m.int("id"), m.int("trackId"), m.str("type"), m.str("name"), m.bool("bypass"),
                m["params"].asList().map { it.asObj() }.map {
                    NativeParam(it.str("id"), it.str("name"), it.str("unit"), it.float("min"), it.float("max"), it.float("def"), it.float("value"))
                }
            )
        } catch (_: JsonException) { null }
    }
}

/** What the project layer needs from the audio engine (implemented over JNI; faked in unit tests). */
interface EngineApi {
    fun resetProject()
    fun addTrack(id: Int): Boolean
    fun removeTrack(id: Int): Boolean
    fun setTrackVolume(id: Int, v: Float)
    fun setTrackPan(id: Int, v: Float)
    fun setTrackMute(id: Int, m: Boolean)
    fun setTrackSolo(id: Int, s: Boolean)
    /** 0 or 4 = ok (4: file was truncated but usable); -1 unknown track; other positive = MediaError. */
    fun setClip(id: Int, trackId: Int, path: String, start: Long, offset: Long, length: Long, gainDb: Float, fadeIn: Long, fadeOut: Long): Int
    fun removeClip(id: Int): Boolean
    fun addEffect(trackId: Int, type: String, index: Int): Int
    fun removeEffect(nativeId: Int): Boolean
    fun setEffectParam(nativeId: Int, index: Int, value: Float): Boolean
    fun setEffectBypass(nativeId: Int, bypass: Boolean): Boolean
    fun effectInfo(nativeId: Int): NativeEffectInfo?
    fun setMasterVolume(v: Float)
    fun setLoop(start: Long, end: Long, enabled: Boolean)
    fun setMetronome(on: Boolean, bpm: Float, beats: Int)
    fun setCountIn(bars: Int)
    fun setTrackSend(id: Int, bus: Int, level: Float)
    fun setBusLevel(bus: Int, level: Float)
    fun setPunch(enabled: Boolean, inFrame: Long, outFrame: Long)
}

/**
 * Keeps the native engine's model equal to a [ProjectDoc]. [sync] applies only the differences
 * between the last applied document and the new one, so dragging a clip or a fader sends a
 * handful of cheap calls instead of rebuilding the session (and never interrupts playback).
 */
class EngineSync(private val api: EngineApi, private val mediaPath: (String) -> String) {

    private class NativeFx(val nativeId: Int, val type: String, val paramIndex: Map<String, Int>)

    data class Report(val clipErrors: Map<Int, Int>, val rebuilt: Boolean)

    private var applied: ProjectDoc? = null
    private val fx = HashMap<Int, NativeFx>()          // doc effect id -> native effect
    private val clipErrors = LinkedHashMap<Int, Int>()

    /** Errors from the last syncs (clip id -> native code) for clips that could not be loaded. */
    fun errors(): Map<Int, Int> = clipErrors

    fun forget() { applied = null; fx.clear(); clipErrors.clear() }

    @Synchronized
    fun sync(doc: ProjectDoc, forceRebuild: Boolean = false): Report {
        val old = applied
        if (old == null || forceRebuild) { rebuild(doc); return Report(HashMap(clipErrors), true) }
        diff(old, doc, initial = false)
        applied = doc
        return Report(HashMap(clipErrors), false)
    }

    private fun rebuild(doc: ProjectDoc) {
        api.resetProject()
        fx.clear(); clipErrors.clear()
        diff(ProjectDoc(), doc, initial = true)
        applied = doc
    }

    private fun diff(old: ProjectDoc, doc: ProjectDoc, initial: Boolean) {
        // master
        if (initial || old.masterVolume != doc.masterVolume) api.setMasterVolume(doc.masterVolume)
        if (initial || old.loopStart != doc.loopStart || old.loopEnd != doc.loopEnd || old.loopEnabled != doc.loopEnabled || initial)
            api.setLoop(doc.loopStart, doc.loopEnd, doc.loopEnabled)
        if (initial || old.metronome != doc.metronome || old.bpm != doc.bpm || old.beatsPerBar != doc.beatsPerBar || initial)
            api.setMetronome(doc.metronome, doc.bpm, doc.beatsPerBar)
        if (initial || old.countInBars != doc.countInBars) api.setCountIn(doc.countInBars)
        if (initial || old.punchEnabled != doc.punchEnabled || old.punchIn != doc.punchIn || old.punchOut != doc.punchOut)
            api.setPunch(doc.punchEnabled, doc.punchIn, doc.punchOut)

        // tracks removed
        val newTrackIds = doc.tracks.map { it.id }.toSet()
        for (t in old.tracks) if (t.id !in newTrackIds) {
            t.effects.forEach { fx.remove(it.id) }
            api.removeTrack(t.id)          // the engine drops the track's clips with it
        }
        // tracks added / changed
        for (t in doc.tracks) {
            val o = old.tracks.firstOrNull { it.id == t.id }
            if (o == null) {
                api.addTrack(t.id)
                api.setTrackVolume(t.id, t.volume); api.setTrackPan(t.id, t.pan)
                api.setTrackMute(t.id, t.mute); api.setTrackSolo(t.id, t.solo)
            } else {
                if (o.volume != t.volume) api.setTrackVolume(t.id, t.volume)
                if (o.pan != t.pan) api.setTrackPan(t.id, t.pan)
                if (o.mute != t.mute) api.setTrackMute(t.id, t.mute)
                if (o.solo != t.solo) api.setTrackSolo(t.id, t.solo)
            }
            for (bi in 0 until MAX_BUSES) {
                val nv = t.sends[bi] ?: 0f
                if (nv != (o?.sends?.get(bi) ?: 0f)) api.setTrackSend(t.id, bi, nv)
            }
            diffEffects(t.id, o?.effects ?: emptyList(), t.effects)
        }
        diffEffects(0, old.masterEffects, doc.masterEffects)

        // aux buses
        for (ob in old.buses) if (doc.bus(ob.index) == null) {
            ob.effects.forEach { e -> fx.remove(e.id)?.let { api.removeEffect(it.nativeId) } }
            api.setBusLevel(ob.index, 1f)
        }
        for (nb in doc.buses) {
            val ob = old.bus(nb.index)
            if (nb.level != (ob?.level ?: 1f)) api.setBusLevel(nb.index, nb.level)
            diffEffects(-(nb.index + 1), ob?.effects ?: emptyList(), nb.effects)
        }

        // clips
        val newClips = doc.clips.associateBy { it.id }
        for (c in old.clips) if (c.id !in newClips) { api.removeClip(c.id); clipErrors.remove(c.id) }
        val oldClips = old.clips.associateBy { it.id }
        for (c in doc.clips) {
            if (oldClips[c.id] == c) continue          // unchanged (a known-bad clip is not retried on every edit)
            val code = api.setClip(c.id, c.trackId, mediaPath(c.media), c.startFrame, c.offsetFrames, c.lengthFrames,
                c.gainDb, c.fadeInFrames, c.fadeOutFrames)
            if (code == 0 || code == 4) clipErrors.remove(c.id) else clipErrors[c.id] = code
        }
    }

    private fun diffEffects(trackId: Int, old: List<EffectDoc>, new: List<EffectDoc>) {
        val newById = new.associateBy { it.id }
        for (e in old) {
            val n = newById[e.id]
            if (n == null || n.type != e.type) { fx.remove(e.id)?.let { api.removeEffect(it.nativeId) } }
        }
        new.forEachIndexed { index, e ->
            val o = old.firstOrNull { it.id == e.id && it.type == e.type }
            var nf = fx[e.id]
            if (o == null || nf == null) {
                val nid = api.addEffect(trackId, e.type, index)
                if (nid <= 0) return@forEachIndexed
                val info = api.effectInfo(nid)
                nf = NativeFx(nid, e.type, info?.params?.mapIndexed { i, p -> p.id to i }?.toMap() ?: emptyMap())
                fx[e.id] = nf
                e.params.forEach { (pid, v) -> nf.paramIndex[pid]?.let { api.setEffectParam(nid, it, v) } }
                if (e.bypass) api.setEffectBypass(nid, true)
            } else {
                for ((pid, v) in e.params) if (o.params[pid] != v) nf.paramIndex[pid]?.let { api.setEffectParam(nf.nativeId, it, v) }
                if (o.bypass != e.bypass) api.setEffectBypass(nf.nativeId, e.bypass)
            }
        }
    }

    /** Native effect id for a document effect (UI uses it to query live parameter info). */
    fun nativeEffectId(docEffectId: Int): Int? = fx[docEffectId]?.nativeId
}
