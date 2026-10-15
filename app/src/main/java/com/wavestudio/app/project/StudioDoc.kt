package com.wavestudio.app.project

import kotlin.math.max
import kotlin.math.min

/** Drum voices of the beat maker (all synthesised in code, no samples shipped: see [Synth]). */
enum class DrumVoice(val id: String, val label: String, val pan: Float) {
    KICK("kick", "Kick", 0f), SNARE("snare", "Snare", 0f), CLAP("clap", "Clap", 0.1f),
    HAT("hat", "Hi-hat", 0.3f), OPEN_HAT("open_hat", "Open hat", 0.3f),
    TOM("tom", "Tom", -0.25f), RIM("rim", "Rim", -0.15f), COWBELL("cowbell", "Cowbell", 0.2f);
    companion object { fun byId(id: String): DrumVoice? = values().firstOrNull { it.id == id } }
}

const val STEPS_PER_BAR = 16
const val MAX_BAR_COUNT = 8
/** MIDI note range of the piano roll. */
const val ROLL_LOW = 24     // C1
const val ROLL_HIGH = 96    // C7

/** One drum lane. [steps] holds one digit per 16th step: 0 off, 1 ghost, 2 normal, 3 accent. */
data class DrumRowDoc(val voice: String, val steps: String, val level: Float = 1f, val mute: Boolean = false) {
    fun at(step: Int): Int = steps.getOrNull(step)?.digitToIntOrNull()?.coerceIn(0, 3) ?: 0
}

data class BeatDoc(
    val bars: Int = 1,
    /** Delay of every second 16th as a fraction of a step (0..0.6): 0 = straight, 0.33 = triplet-ish shuffle. */
    val swing: Float = 0f,
    val rows: List<DrumRowDoc> = defaultRows(1)
) {
    val totalSteps: Int get() = bars * STEPS_PER_BAR
    companion object {
        fun defaultRows(bars: Int): List<DrumRowDoc> = DrumVoice.values().map { DrumRowDoc(it.id, "0".repeat(bars * STEPS_PER_BAR)) }
    }
}

/** A note in the piano roll; [step] and [length] are in 16th notes. */
data class RollNote(val pitch: Int, val step: Int, val length: Int, val velocity: Float = 0.8f) {
    val end: Int get() = step + length
}

data class RollDoc(val bars: Int = 2, val instrument: String = "piano", val notes: List<RollNote> = emptyList()) {
    val totalSteps: Int get() = bars * STEPS_PER_BAR
}

data class StudioDoc(val beat: BeatDoc = BeatDoc(), val roll: RollDoc = RollDoc())

/** Pure editing operations for the beat maker and piano roll. Every result is normalised and in range. */
object StudioOps {

    private val CYCLE = intArrayOf(0, 2, 3, 1)   // tap order: off → normal → accent → ghost → off

    fun normalizeBeat(b: BeatDoc): BeatDoc {
        val bars = b.bars.coerceIn(1, MAX_BAR_COUNT)
        val n = bars * STEPS_PER_BAR
        val byVoice = b.rows.associateBy { it.voice }
        val rows = DrumVoice.values().map { v ->
            val r = byVoice[v.id]
            val s = (r?.steps ?: "").filter { it in '0'..'3' }.padEnd(n, '0').take(n)
            DrumRowDoc(v.id, s, (r?.level ?: 1f).let { if (it.isNaN()) 1f else it.coerceIn(0f, 1.5f) }, r?.mute ?: false)
        }
        return BeatDoc(bars, if (b.swing.isNaN()) 0f else b.swing.coerceIn(0f, 0.6f), rows)
    }

    fun normalizeRoll(r: RollDoc): RollDoc {
        val bars = r.bars.coerceIn(1, MAX_BAR_COUNT)
        val n = bars * STEPS_PER_BAR
        val notes = r.notes.mapNotNull { x ->
            if (x.pitch !in ROLL_LOW..ROLL_HIGH || x.step < 0 || x.step >= n) null
            else RollNote(x.pitch, x.step, x.length.coerceIn(1, n - x.step), if (x.velocity.isNaN()) 0.8f else x.velocity.coerceIn(0.05f, 1f))
        }.sortedWith(compareBy({ it.step }, { it.pitch }))
        return RollDoc(bars, r.instrument, notes)
    }

    fun normalize(d: StudioDoc) = StudioDoc(normalizeBeat(d.beat), normalizeRoll(d.roll))

    // ---- beat

    fun cycleStep(b: BeatDoc, voice: DrumVoice, step: Int): BeatDoc {
        val nb = normalizeBeat(b)
        if (step !in 0 until nb.totalSteps) return nb
        return nb.copy(rows = nb.rows.map { r ->
            if (r.voice != voice.id) r else {
                val cur = r.at(step)
                val next = CYCLE[(CYCLE.indexOf(cur) + 1) % CYCLE.size]
                r.copy(steps = r.steps.substring(0, step) + next + r.steps.substring(step + 1))
            }
        })
    }

    fun setBars(b: BeatDoc, bars: Int): BeatDoc = normalizeBeat(b.copy(bars = bars))      // grows with silence, shrinks by cutting

    fun setSwing(b: BeatDoc, swing: Float) = normalizeBeat(b.copy(swing = swing))

    fun setRow(b: BeatDoc, voice: DrumVoice, level: Float? = null, mute: Boolean? = null): BeatDoc =
        normalizeBeat(b).let { nb -> nb.copy(rows = nb.rows.map { if (it.voice == voice.id) it.copy(level = level ?: it.level, mute = mute ?: it.mute) else it }) }

    fun clearBeat(b: BeatDoc): BeatDoc = BeatDoc(b.bars.coerceIn(1, MAX_BAR_COUNT), b.swing, BeatDoc.defaultRows(b.bars.coerceIn(1, MAX_BAR_COUNT))).let { normalizeBeat(it) }

    fun hitCount(b: BeatDoc): Int = b.rows.filter { !it.mute }.sumOf { r -> r.steps.count { it != '0' } }

    /** A classic four-on-the-floor starter pattern so a new user sees something sensible. */
    fun demoBeat(bars: Int = 1): BeatDoc {
        var b = normalizeBeat(BeatDoc(bars = bars))
        fun on(v: DrumVoice, steps: List<Int>, level: Int) {
            for (bar in 0 until bars) for (s in steps) {
                val i = bar * STEPS_PER_BAR + s
                b = b.copy(rows = b.rows.map { r -> if (r.voice != v.id) r else r.copy(steps = r.steps.substring(0, i) + level + r.steps.substring(i + 1)) })
            }
        }
        on(DrumVoice.KICK, listOf(0, 4, 8, 12), 3)
        on(DrumVoice.SNARE, listOf(4, 12), 3)
        on(DrumVoice.HAT, listOf(0, 2, 4, 6, 8, 10, 12, 14), 2)
        on(DrumVoice.OPEN_HAT, listOf(14), 1)
        return b
    }

    // ---- piano roll

    fun noteAt(r: RollDoc, pitch: Int, step: Int): RollNote? = r.notes.firstOrNull { it.pitch == pitch && step >= it.step && step < it.end }

    /** Places a note; any same-pitch note it overlaps is replaced (a roll never holds two voices on one pitch at once). */
    fun addNote(r: RollDoc, pitch: Int, step: Int, length: Int, velocity: Float = 0.8f): RollDoc {
        val nr = normalizeRoll(r)
        if (pitch !in ROLL_LOW..ROLL_HIGH || step !in 0 until nr.totalSteps) return nr
        val len = length.coerceIn(1, nr.totalSteps - step)
        val kept = nr.notes.filterNot { it.pitch == pitch && it.step < step + len && it.end > step }
        return normalizeRoll(nr.copy(notes = kept + RollNote(pitch, step, len, velocity)))
    }

    fun removeNoteAt(r: RollDoc, pitch: Int, step: Int): RollDoc {
        val n = noteAt(r, pitch, step) ?: return normalizeRoll(r)
        return normalizeRoll(r.copy(notes = r.notes - n))
    }

    fun setRollBars(r: RollDoc, bars: Int) = normalizeRoll(r.copy(bars = bars))
    fun setInstrument(r: RollDoc, id: String) = r.copy(instrument = id)
    fun clearRoll(r: RollDoc) = r.copy(notes = emptyList())

    fun transpose(r: RollDoc, semitones: Int): RollDoc =
        normalizeRoll(r.copy(notes = r.notes.map { it.copy(pitch = it.pitch + semitones) }))

    fun demoRoll(): RollDoc = normalizeRoll(RollDoc(bars = 2, notes = listOf(
        // C major, Am, F, G arpeggio feel
        RollNote(60, 0, 4), RollNote(64, 4, 4), RollNote(67, 8, 4), RollNote(72, 12, 4),
        RollNote(57, 16, 4), RollNote(60, 20, 4), RollNote(64, 24, 4), RollNote(69, 28, 4)
    )))

    fun isBlackKey(pitch: Int): Boolean = (pitch % 12) in intArrayOf(1, 3, 6, 8, 10)
    private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun pitchName(pitch: Int): String = NAMES[((pitch % 12) + 12) % 12] + (Math.floorDiv(pitch, 12) - 1)
    fun clampLen(l: Int, steps: Int) = max(1, min(l, steps))
}
