package com.wavestudio.app.project

/**
 * The persisted, versioned project document. Everything is immutable: an edit produces a new
 * document, which makes undo/redo a list of snapshots and autosave a plain serialisation of a
 * consistent state.
 *
 * Time base: all frame positions are in timeline frames at [TIMELINE_RATE] Hz, independent of the
 * audio device rate (the native engine converts to the device rate).
 */
const val TIMELINE_RATE = 48_000
const val PROJECT_FORMAT_VERSION = 1
/** Number of aux send buses the engine provides. */
const val MAX_BUSES = 4

data class EffectDoc(
    val id: Int,
    val type: String,
    val bypass: Boolean = false,
    /** Parameter values by stable parameter id (e.g. "threshold"). Missing ids use the native default. */
    val params: Map<String, Float> = emptyMap()
)

data class TrackDoc(
    val id: Int,
    val name: String,
    val color: Long = 0xFF4A90E2L,
    val volume: Float = 1f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val arm: Boolean = false,
    val effects: List<EffectDoc> = emptyList(),
    /** Aux send levels (post-fader) by bus index; a missing entry means no send. Linear 0..2. */
    val sends: Map<Int, Float> = emptyMap()
)

/** An aux send bus: the summed sends go through [effects] and come back into the master mix at [level]. */
data class BusDoc(val index: Int, val name: String, val level: Float = 1f, val effects: List<EffectDoc> = emptyList())

data class ClipDoc(
    val id: Int,
    val trackId: Int,
    /** Path relative to the project directory (e.g. "media/audio_1.wav"). Never an absolute path. */
    val media: String,
    val name: String = "",
    val startFrame: Long,
    val offsetFrames: Long = 0,
    val lengthFrames: Long,
    /** Length of the whole source media in timeline frames; bounds trimming. */
    val sourceFrames: Long,
    val gainDb: Float = 0f,
    val fadeInFrames: Long = 0,
    val fadeOutFrames: Long = 0
) {
    val endFrame: Long get() = startFrame + lengthFrames
}

data class MarkerDoc(val id: Int, val frame: Long, val name: String)

data class ProjectDoc(
    val version: Int = PROJECT_FORMAT_VERSION,
    val name: String = "Untitled Project",
    val bpm: Float = 120f,
    val beatsPerBar: Int = 4,
    val beatUnit: Int = 4,
    val masterVolume: Float = 1f,
    val masterEffects: List<EffectDoc> = emptyList(),
    val buses: List<BusDoc> = emptyList(),
    val tracks: List<TrackDoc> = emptyList(),
    val clips: List<ClipDoc> = emptyList(),
    val markers: List<MarkerDoc> = emptyList(),
    val loopStart: Long = 0,
    val loopEnd: Long = 0,
    val loopEnabled: Boolean = false,
    /** Punch recording: while recording, only [punchIn, punchOut) is written to the take. */
    val punchEnabled: Boolean = false,
    val punchIn: Long = 0,
    val punchOut: Long = 0,
    val metronome: Boolean = false,
    val countInBars: Int = 0,
    val nextId: Int = 1,
    val created: Long = 0,
    val modified: Long = 0
) {
    val endFrame: Long get() = clips.maxOfOrNull { it.endFrame } ?: 0L
    fun track(id: Int): TrackDoc? = tracks.firstOrNull { it.id == id }
    fun bus(index: Int): BusDoc? = buses.firstOrNull { it.index == index }
    fun clip(id: Int): ClipDoc? = clips.firstOrNull { it.id == id }
    val framesPerBeat: Double get() = TIMELINE_RATE * 60.0 / bpm.coerceAtLeast(1f)
}
