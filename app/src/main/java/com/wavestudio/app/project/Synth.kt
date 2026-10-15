package com.wavestudio.app.project

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

class StereoBuffer(val left: FloatArray, val right: FloatArray) {
    val frames: Int get() = left.size
    fun peak(): Float { var p = 0f; for (i in left.indices) p = max(p, max(abs(left[i]), abs(right[i]))); return p }
}

enum class Wave { SINE, SAW, SQUARE, TRIANGLE, ORGAN }

/** Simple subtractive synth voice description: oscillator, ADSR (seconds), low-pass cutoff (0 = off) and unison detune. */
class InstrumentSpec(
    val id: String, val label: String, val wave: Wave,
    val attack: Float, val decay: Float, val sustain: Float, val release: Float,
    val cutoffHz: Float, val detuneCents: Float, val gain: Float
)

object Instruments {
    // Honest naming: these are synthesised instruments, not sampled pianos.
    val all = listOf(
        InstrumentSpec("keys", "Keys (synth piano)", Wave.TRIANGLE, 0.003f, 0.9f, 0.12f, 0.35f, 4200f, 6f, 0.9f),
        InstrumentSpec("pluck", "Pluck", Wave.SAW, 0.002f, 0.35f, 0.0f, 0.2f, 3000f, 8f, 0.8f),
        InstrumentSpec("lead", "Lead", Wave.SQUARE, 0.01f, 0.2f, 0.7f, 0.15f, 5500f, 10f, 0.55f),
        InstrumentSpec("bass", "Bass", Wave.SAW, 0.004f, 0.25f, 0.6f, 0.12f, 700f, 0f, 0.9f),
        InstrumentSpec("pad", "Pad", Wave.SAW, 0.35f, 0.6f, 0.8f, 0.9f, 2400f, 14f, 0.5f),
        InstrumentSpec("organ", "Organ", Wave.ORGAN, 0.01f, 0.05f, 0.95f, 0.08f, 0f, 0f, 0.7f),
        InstrumentSpec("sine", "Sine", Wave.SINE, 0.005f, 0.1f, 0.9f, 0.15f, 0f, 0f, 0.9f)
    )
    fun byId(id: String): InstrumentSpec = all.firstOrNull { it.id == id } ?: all[0]
}

/**
 * Offline, deterministic synthesis for the beat maker and piano roll. Everything is generated from formulas (no samples,
 * no network); the result is an ordinary stereo buffer that is written to a WAV and imported as a clip, so it plays,
 * mixes, takes effects and exports exactly like recorded audio.
 */
object Synth {
    const val RATE = 48000

    private class Rng(var s: Int) {
        fun next(): Float { s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5); return s / 2147483648f }
    }

    private fun softClip(x: Float): Float {
        val a = abs(x)
        return if (a < 0.8f) x else (if (x < 0) -1f else 1f) * (0.8f + 0.2f * tanh((a - 0.8f) / 0.2f))
    }

    // ------------------------------------------------------------------ drums

    /** One drum hit as a mono buffer with a peak of 0.9. */
    fun drumHit(v: DrumVoice, rate: Int = RATE): FloatArray {
        val secs = when (v) {
            DrumVoice.KICK -> 0.5f; DrumVoice.SNARE -> 0.3f; DrumVoice.CLAP -> 0.35f; DrumVoice.HAT -> 0.12f
            DrumVoice.OPEN_HAT -> 0.6f; DrumVoice.TOM -> 0.5f; DrumVoice.RIM -> 0.1f; DrumVoice.COWBELL -> 0.5f
        }
        val n = (secs * rate).toInt()
        val out = FloatArray(n)
        val rng = Rng(0x2545F491 + v.ordinal * 7919)
        var phase = 0.0; var phase2 = 0.0
        var prev = 0f; var prev2 = 0f; var lp = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val tf = t.toFloat()
            out[i] = when (v) {
                DrumVoice.KICK -> {
                    phase += 2 * PI * (48.0 + 120.0 * exp(-t / 0.028)) / rate
                    (sin(phase) * exp(-t / 0.16) * minOf(1.0, t / 0.002) + rng.next() * exp(-t / 0.003) * 0.2).toFloat()
                }
                DrumVoice.SNARE -> {
                    val x = rng.next(); val hp = x - prev; prev = x
                    (sin(2 * PI * 185 * t) * exp(-t / 0.05) * 0.5 + sin(2 * PI * 330 * t) * exp(-t / 0.035) * 0.25 + hp * 0.5 * exp(-t / 0.08)).toFloat()
                }
                DrumVoice.CLAP -> {
                    val x = rng.next(); val hp = x - prev; prev = x
                    lp += 0.6f * (hp - lp)
                    val env = if (tf < 0.03f) exp(-(tf % 0.01f) / 0.0025f) else exp(-(tf - 0.03f) / 0.09f) * 0.7f
                    lp * env
                }
                DrumVoice.HAT, DrumVoice.OPEN_HAT -> {
                    val x = rng.next(); val d1 = x - prev; prev = x; val d2 = d1 - prev2; prev2 = d1
                    val tau = if (v == DrumVoice.HAT) 0.022f else 0.16f
                    d2 * 0.45f * exp(-tf / tau) * minOf(1f, tf / 0.0008f)
                }
                DrumVoice.TOM -> {
                    phase += 2 * PI * (95.0 + 85.0 * exp(-t / 0.05)) / rate
                    (sin(phase) * exp(-t / 0.17)).toFloat()
                }
                DrumVoice.RIM -> ((sin(2 * PI * 480 * t) + 0.6 * sin(2 * PI * 1750 * t)) * exp(-t / 0.012)).toFloat()
                DrumVoice.COWBELL -> {
                    phase += 562.0 / rate; phase2 += 845.0 / rate
                    val sq = (if (phase % 1.0 < 0.5) 1f else -1f) * 0.3f + (if (phase2 % 1.0 < 0.5) 1f else -1f) * 0.3f
                    lp += 0.35f * (sq - lp)
                    lp * exp(-tf / 0.12f)
                }
            }
        }
        var pk = 0f
        for (s in out) pk = max(pk, abs(s))
        if (pk > 0f) { val g = 0.9f / pk; for (i in 0 until n) out[i] *= g }
        return out
    }

    private val DRUM_VEL = floatArrayOf(0f, 0.4f, 0.75f, 1f)

    /**
     * Renders the pattern for the given tempo. With [wrap] the length is exactly the pattern length and tails that run past
     * the end wrap to the start, so the clip loops without a gap or a click. Otherwise a tail is appended.
     */
    fun renderBeat(beat: BeatDoc, bpm: Float, rate: Int = RATE, wrap: Boolean = true): StereoBuffer {
        val b = StudioOps.normalizeBeat(beat)
        val stepFrames = rate * 60.0 / bpm.coerceIn(20f, 400f) / 4.0
        val body = (b.totalSteps * stepFrames).roundToInt().coerceAtLeast(1)
        val len = if (wrap) body else body + (rate * 0.6).toInt()
        val l = FloatArray(len); val r = FloatArray(len)
        val hits = HashMap<DrumVoice, FloatArray>()
        for (row in b.rows) {
            if (row.mute || row.level <= 0f) continue
            val voice = DrumVoice.byId(row.voice) ?: continue
            val pan = voice.pan
            val gl = (if (pan > 0f) 1f - pan else 1f) * 0.7f * row.level
            val gr = (if (pan < 0f) 1f + pan else 1f) * 0.7f * row.level
            for (step in 0 until b.totalSteps) {
                val vel = DRUM_VEL[row.at(step)]
                if (vel == 0f) continue
                val sample = hits.getOrPut(voice) { drumHit(voice, rate) }
                val start = (step * stepFrames + (if (step % 2 == 1) b.swing * stepFrames else 0.0)).roundToInt()
                for (k in sample.indices) {
                    var idx = start + k
                    if (idx >= len) { if (!wrap) break; idx %= len }
                    val s = sample[k] * vel
                    l[idx] += s * gl; r[idx] += s * gr
                }
            }
        }
        for (i in 0 until len) { l[i] = softClip(l[i]); r[i] = softClip(r[i]) }
        return StereoBuffer(l, r)
    }

    // ------------------------------------------------------------------ melodic

    fun midiToHz(p: Int): Double = 440.0 * 2.0.pow((p - 69) / 12.0)

    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }

    private fun osc(w: Wave, ph: Double, dt: Double): Double = when (w) {
        Wave.SINE -> sin(2 * PI * ph)
        Wave.SAW -> 2 * ph - 1.0 - blep(ph, dt)
        Wave.SQUARE -> (if (ph < 0.5) 1.0 else -1.0) + blep(ph, dt) - blep((ph + 0.5) % 1.0, dt)
        Wave.TRIANGLE -> 4.0 * abs(ph - 0.5) - 1.0
        Wave.ORGAN -> (sin(2 * PI * ph) + 0.5 * sin(4 * PI * ph) + 0.3 * sin(6 * PI * ph) + 0.15 * sin(8 * PI * ph)) / 1.6
    }

    /** Adds one note into [l]/[r] starting at frame [start] (negative values are clipped), holding for [hold] frames. */
    private fun addNote(
        l: FloatArray, r: FloatArray, inst: InstrumentSpec, pitch: Int, vel: Float,
        start: Int, hold: Int, rate: Int, wrap: Boolean
    ) {
        val len = l.size
        val f = midiToHz(pitch)
        if (f * 2 >= rate) return
        val rel = max(0.01f, inst.release)
        val relFrames = (rel * rate).toInt()
        val total = hold + relFrames
        val att = max(0.002f, inst.attack)
        val dec = max(0.01f, inst.decay)
        val dt1 = f / rate
        val f2 = f * 2.0.pow(inst.detuneCents / 1200.0)
        val dt2 = f2 / rate
        val detuned = inst.detuneCents != 0f
        val a = if (inst.cutoffHz > 0f) 1.0 - exp(-2 * PI * kotlin.math.min(inst.cutoffHz * (0.5 + vel), rate * 0.45) / rate) else 1.0
        var ph1 = 0.0; var ph2 = 0.37
        var y1 = 0.0; var y2 = 0.0
        val amp = vel * inst.gain * 0.35
        var levelAtOff = 0.0
        for (k in 0 until total) {
            val t = k.toDouble() / rate
            val env = if (k < hold) {
                val e = if (t < att) t / att else inst.sustain + (1.0 - inst.sustain) * exp(-(t - att) * 4.0 / dec)
                if (k == hold - 1) levelAtOff = e
                e
            } else {
                val x = (k - hold).toDouble() / relFrames
                levelAtOff * (1.0 - x) * (1.0 - x)
            }
            val o1 = osc(inst.wave, ph1, dt1); ph1 += dt1; if (ph1 >= 1.0) ph1 -= 1.0
            y1 += a * (o1 - y1)
            var sl: Double; var sr: Double
            if (detuned) {
                val o2 = osc(inst.wave, ph2, dt2); ph2 += dt2; if (ph2 >= 1.0) ph2 -= 1.0
                y2 += a * (o2 - y2)
                sl = y1 * 0.8 + y2 * 0.4; sr = y1 * 0.4 + y2 * 0.8
            } else { sl = y1; sr = y1 }
            var idx = start + k
            if (idx < 0) continue
            if (idx >= len) { if (!wrap) break; idx %= len }
            l[idx] += (sl * env * amp).toFloat(); r[idx] += (sr * env * amp).toFloat()
        }
    }

    fun renderRoll(roll: RollDoc, bpm: Float, rate: Int = RATE, wrap: Boolean = false): StereoBuffer {
        val ro = StudioOps.normalizeRoll(roll)
        val inst = Instruments.byId(ro.instrument)
        val stepFrames = rate * 60.0 / bpm.coerceIn(20f, 400f) / 4.0
        val body = (ro.totalSteps * stepFrames).roundToInt().coerceAtLeast(1)
        val len = if (wrap) body else body + (inst.release * rate).toInt() + 64
        val l = FloatArray(len); val r = FloatArray(len)
        for (n in ro.notes) addNote(l, r, inst, n.pitch, n.velocity, (n.step * stepFrames).roundToInt(), (n.length * stepFrames).roundToInt().coerceAtLeast(1), rate, wrap)
        for (i in 0 until len) { l[i] = softClip(l[i]); r[i] = softClip(r[i]) }
        return StereoBuffer(l, r)
    }

    /** A single note for the on-screen keyboard (audition). */
    fun renderPreviewNote(instrumentId: String, pitch: Int, seconds: Float = 0.6f, rate: Int = RATE): StereoBuffer {
        val inst = Instruments.byId(instrumentId)
        val hold = (seconds * rate).toInt()
        val len = hold + (inst.release * rate).toInt() + 64
        val l = FloatArray(len); val r = FloatArray(len)
        addNote(l, r, inst, pitch, 0.8f, 0, hold, rate, false)
        for (i in 0 until len) { l[i] = softClip(l[i]); r[i] = softClip(r[i]) }
        return StereoBuffer(l, r)
    }
}

/** Minimal WAV writer (PCM 16/24-bit, stereo) for rendered studio audio. */
object WavOut {
    fun write(file: File, buf: StereoBuffer, rate: Int = Synth.RATE, bits: Int = 24) {
        require(bits == 16 || bits == 24)
        val bytes = bits / 8
        val dataLen = buf.frames.toLong() * 2 * bytes
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".part")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt((36 + dataLen).toInt()); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2)
            h.putInt(rate); h.putInt(rate * 2 * bytes); h.putShort((2 * bytes).toShort()); h.putShort(bits.toShort())
            h.put("data".toByteArray()); h.putInt(dataLen.toInt())
            raf.write(h.array())
            val chunk = ByteBuffer.allocate(4096 * 2 * bytes).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < buf.frames) {
                chunk.clear()
                val end = minOf(buf.frames, i + 4096)
                for (k in i until end) for (c in 0..1) {
                    val s = (if (c == 0) buf.left[k] else buf.right[k]).let { if (it.isNaN()) 0f else it.coerceIn(-1f, 1f) }
                    if (bits == 16) chunk.putShort(Math.round(s * 32767f).toShort())
                    else { val v = Math.round(s * 8388607f); chunk.put(v.toByte()); chunk.put((v shr 8).toByte()); chunk.put((v shr 16).toByte()) }
                }
                raf.write(chunk.array(), 0, chunk.position())
                i = end
            }
            raf.fd.sync()
        }
        if (!tmp.renameTo(file)) { tmp.delete(); throw java.io.IOException("Cannot write ${file.name}") }
    }
}
