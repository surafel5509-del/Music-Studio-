package com.wavestudio.app.project

/** Thrown when a project file was written by a newer app version and must not be opened/overwritten. */
class UnsupportedProjectVersion(val found: Int) : Exception("Project format v$found is newer than this app supports (v$PROJECT_FORMAT_VERSION)")

object ProjectCodec {

    fun encode(doc: ProjectDoc): String = Json.write(toMap(doc), pretty = true)

    /** Parses and migrates. Throws [JsonException] for corrupt text and [UnsupportedProjectVersion] for newer files. */
    fun decode(text: String): ProjectDoc = fromMap(Json.parseObject(text))

    fun toMap(d: ProjectDoc): Map<String, Any?> = linkedMapOf(
        "app" to "WaveStudio",
        "version" to d.version,
        "name" to d.name,
        "bpm" to d.bpm,
        "beatsPerBar" to d.beatsPerBar,
        "beatUnit" to d.beatUnit,
        "masterVolume" to d.masterVolume,
        "masterEffects" to d.masterEffects.map(::effectMap),
        "tracks" to d.tracks.map { t ->
            linkedMapOf<String, Any?>(
                "id" to t.id, "name" to t.name, "color" to t.color, "volume" to t.volume, "pan" to t.pan,
                "mute" to t.mute, "solo" to t.solo, "arm" to t.arm, "effects" to t.effects.map(::effectMap)
            )
        },
        "clips" to d.clips.map { c ->
            linkedMapOf<String, Any?>(
                "id" to c.id, "trackId" to c.trackId, "media" to c.media, "name" to c.name,
                "start" to c.startFrame, "offset" to c.offsetFrames, "length" to c.lengthFrames,
                "sourceFrames" to c.sourceFrames, "gainDb" to c.gainDb,
                "fadeIn" to c.fadeInFrames, "fadeOut" to c.fadeOutFrames
            )
        },
        "markers" to d.markers.map { linkedMapOf<String, Any?>("id" to it.id, "frame" to it.frame, "name" to it.name) },
        "loop" to linkedMapOf<String, Any?>("start" to d.loopStart, "end" to d.loopEnd, "enabled" to d.loopEnabled),
        "punch" to linkedMapOf<String, Any?>("in" to d.punchIn, "out" to d.punchOut, "enabled" to d.punchEnabled),
        "metronome" to d.metronome,
        "countInBars" to d.countInBars,
        "nextId" to d.nextId,
        "created" to d.created,
        "modified" to d.modified
    )

    private fun effectMap(e: EffectDoc): Map<String, Any?> = linkedMapOf(
        "id" to e.id, "type" to e.type, "bypass" to e.bypass,
        "params" to LinkedHashMap<String, Any?>().also { m -> e.params.forEach { (k, v) -> m[k] = v } }
    )

    private fun effectFrom(m: Map<String, Any?>): EffectDoc = EffectDoc(
        id = m.int("id"), type = m.str("type"), bypass = m.bool("bypass"),
        params = m["params"].asObj().mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toFloat() } }.toMap()
    )

    fun fromMap(m: Map<String, Any?>): ProjectDoc {
        val version = m.int("version", 0)
        if (version > PROJECT_FORMAT_VERSION) throw UnsupportedProjectVersion(version)
        // Version 0 = files written before the format was versioned (or hand-edited): every
        // field is optional, so the same reader accepts them; v0 had no sourceFrames, so the
        // clip length is used as the best known bound.
        val tracks = m["tracks"].asList().map { it.asObj() }.map { t ->
            TrackDoc(
                id = t.int("id"), name = t.str("name", "Track"), color = t.long("color", 0xFF4A90E2L),
                volume = t.float("volume", 1f).coerceIn(0f, 4f), pan = t.float("pan", 0f).coerceIn(-1f, 1f),
                mute = t.bool("mute"), solo = t.bool("solo"), arm = t.bool("arm"),
                effects = t["effects"].asList().map { effectFrom(it.asObj()) }
            )
        }.filter { it.id > 0 }
        val clips = m["clips"].asList().map { it.asObj() }.map { c ->
            val len = c.long("length").coerceAtLeast(0)
            val off = c.long("offset").coerceAtLeast(0)
            ClipDoc(
                id = c.int("id"), trackId = c.int("trackId"), media = c.str("media"), name = c.str("name"),
                startFrame = c.long("start").coerceAtLeast(0), offsetFrames = off, lengthFrames = len,
                sourceFrames = c.long("sourceFrames", off + len).coerceAtLeast(off + len),
                gainDb = c.float("gainDb"), fadeInFrames = c.long("fadeIn").coerceAtLeast(0),
                fadeOutFrames = c.long("fadeOut").coerceAtLeast(0)
            )
        }.filter { it.id > 0 && it.lengthFrames > 0 && it.media.isNotEmpty() }
        val loop = m["loop"].asObj()
        val punch = m["punch"].asObj()
        val keptClips = clips   // clips whose track is missing are kept (not silently deleted); the engine just skips them
        val maxId = (tracks.map { it.id } + keptClips.map { it.id } +
            tracks.flatMap { t -> t.effects.map { it.id } } + m["masterEffects"].asList().map { it.asObj().int("id") } +
            m["markers"].asList().map { it.asObj().int("id") }).maxOrNull() ?: 0
        return ProjectDoc(
            version = PROJECT_FORMAT_VERSION,
            name = m.str("name", "Untitled Project"),
            bpm = m.float("bpm", 120f).coerceIn(20f, 400f),
            beatsPerBar = m.int("beatsPerBar", 4).coerceIn(1, 16),
            beatUnit = m.int("beatUnit", 4).let { if (it in setOf(2, 4, 8, 16)) it else 4 },
            masterVolume = m.float("masterVolume", 1f).coerceIn(0f, 2f),
            masterEffects = m["masterEffects"].asList().map { effectFrom(it.asObj()) },
            tracks = tracks,
            clips = keptClips,
            markers = m["markers"].asList().map { it.asObj() }.map { MarkerDoc(it.int("id"), it.long("frame"), it.str("name")) },
            loopStart = loop.long("start"), loopEnd = loop.long("end"), loopEnabled = loop.bool("enabled"),
            punchIn = punch.long("in").coerceAtLeast(0), punchOut = punch.long("out").coerceAtLeast(0),
            punchEnabled = punch.bool("enabled") && punch.long("out") > punch.long("in"),
            metronome = m.bool("metronome"), countInBars = m.int("countInBars").coerceIn(0, 4),
            nextId = maxOf(m.int("nextId", 1), maxId + 1),
            created = m.long("created"), modified = m.long("modified")
        )
    }
}
