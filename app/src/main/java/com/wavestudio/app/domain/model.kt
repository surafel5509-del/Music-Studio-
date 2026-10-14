package com.wavestudio.app.domain

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: Long = 0L,
    val name: String = "Untitled Project",
    val sampleRate: Float = 48000f,
    val bitDepth: Int = 24,
    val channels: Int = 2,
    val bpm: Float = 120f,
    val timeSignatureNum: Int = 4,
    val timeSignatureDen: Int = 4,
    val created: Long = System.currentTimeMillis(),
    val modified: Long = System.currentTimeMillis(),
    val filePath: String = "",
    val durationFrames: Long = 0L
)

@Serializable
data class Track(
    val id: Int = 0,
    val projectId: Long = 0L,
    val name: String = "Track",
    val color: ULong = 0xFF4A90E2UL,
    val volume: Float = 1f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val arm: Boolean = false,
    val inputChannel: Int = 0,
    val effectChain: List<EffectPreset> = emptyList(),
    val type: TrackType = TrackType.AUDIO
)

@Serializable
enum class TrackType { AUDIO, MIDI, BUS, MASTER }

@Serializable
data class AudioClip(
    val id: Int = 0,
    val trackId: Int = 0,
    val sourcePath: String = "",
    val startFrame: Long = 0,
    val offsetFrames: Long = 0,
    val lengthFrames: Long = 0,
    val gainDb: Float = 0f,
    val fadeInMs: Float = 0f,
    val fadeOutMs: Float = 0f,
    val reversed: Boolean = false,
    val pitchSemitones: Float = 0f,
    val stretchRatio: Float = 1f,
    val name: String = ""
)

@Serializable
data class MidiClip(
    val id: Int = 0,
    val trackId: Int = 0,
    val startFrame: Long = 0,
    val lengthFrames: Long = 0,
    val events: List<MidiEventData> = emptyList()
)

@Serializable
data class MidiEventData(
    val frame: Long = 0,
    val status: Int = 0,
    val data1: Int = 0,
    val data2: Int = 0
)

@Serializable
data class Automation(
    val id: Int = 0,
    val trackId: Int = 0,
    val targetId: String = "", // effect.paramid or "volume"/"pan"
    val points: List<AutomationPoint> = emptyList()
)

@Serializable
data class AutomationPoint(
    val frame: Long = 0,
    val value: Float = 0f,
    val curve: Float = 0f // 0=linear
)

@Serializable
data class Marker(
    val id: Int = 0,
    val frame: Long = 0,
    val name: String = ""
)

@Serializable
data class EffectPreset(
    val id: String = "",
    val type: EffectType = EffectType.EQ,
    val name: String = "",
    val enabled: Boolean = true,
    val parameters: Map<String, Float> = emptyMap()
)

@Serializable
enum class EffectType {
    EQ, COMPRESSOR, LIMITER, REVERB, DELAY, CHORUS, FLANGER, PHASER,
    DISTORTION, BITCRUSHER, FILTER, NOISE_GATE, DE_ESSER,
    TUNER, GAIN, INVERT
}

@Serializable
data class PluginInfo(
    val id: String,
    val name: String,
    val vendor: String = "",
    val version: String = "1.0",
    val category: PluginCategory = PluginCategory.EFFECT,
    val path: String = "",
    val isBuiltIn: Boolean = true,
    val numInputs: Int = 2,
    val numOutputs: Int = 2
)

@Serializable
enum class PluginCategory { EFFECT, INSTRUMENT, UTILITY }

data class Meters(
    val peakL: Float = 0f,
    val peakR: Float = 0f,
    val rmsL: Float = 0f,
    val rmsR: Float = 0f
)

fun dbToLinear(db: Float): Float = Math.pow(10.0, db / 20.0).toFloat()
fun linearToDb(lin: Float): Float = (20.0 * Math.log10(lin.toDouble().coerceAtLeast(1e-10))).toFloat()
