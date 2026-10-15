package com.wavestudio.app.project

/** One effect in a preset chain: native effect type id + parameter values by parameter id. */
data class PresetFx(val type: String, val params: Map<String, Float> = emptyMap())

/** A named chain of built-in effects. Applying it only creates ordinary [EffectDoc]s: nothing special at runtime. */
data class EffectPreset(val id: String, val name: String, val category: String, val description: String, val chain: List<PresetFx>)

object EffectPresets {
    val all: List<EffectPreset> = listOf(
        EffectPreset("voice_clean", "Voice Clean", "Voice",
            "Low-cut, noise reduction, de-ess and gentle compression for a recorded voice.",
            listOf(
                PresetFx("filter", mapOf("type" to 1f, "freq" to 90f, "res" to 0.7f)),
                PresetFx("denoise", mapOf("reduction" to 16f, "sensitivity" to 0.5f)),
                PresetFx("deesser", mapOf("threshold" to -24f, "freq" to 6500f, "amount" to 0.5f)),
                PresetFx("compressor", mapOf("threshold" to -20f, "ratio" to 3f, "attack" to 8f, "release" to 120f, "makeup" to 3f))
            )),
        EffectPreset("vocal_tune", "Vocal Tune (natural)", "Voice",
            "Auto-Tune with a relaxed retune speed, then compression and a little room. Set Key/Scale for your song.",
            listOf(
                PresetFx("autotune", mapOf("speed" to 60f, "amount" to 1f)),
                PresetFx("compressor", mapOf("threshold" to -18f, "ratio" to 3f, "makeup" to 2f)),
                PresetFx("reverb", mapOf("roomsize" to 0.4f, "wet" to 0.12f))
            )),
        EffectPreset("hard_tune", "Hard Tune (robot pop)", "Voice",
            "Instant pitch snapping (retune speed 0) for the hard-tuned vocal sound.",
            listOf(PresetFx("autotune", mapOf("speed" to 0f, "amount" to 1f)))),
        EffectPreset("chipmunk", "Chipmunk", "Voice changer",
            "Higher pitch and brighter formants.",
            listOf(PresetFx("voice_changer", mapOf("pitch" to 7f, "formant" to 5f)))),
        EffectPreset("deep_voice", "Deep Voice", "Voice changer",
            "Lower pitch and darker formants.",
            listOf(PresetFx("voice_changer", mapOf("pitch" to -6f, "formant" to -4f)))),
        EffectPreset("robot", "Robot", "Voice changer",
            "Ring-modulated metallic voice.",
            listOf(PresetFx("voice_changer", mapOf("robot" to 0.85f, "robot_freq" to 70f)))),
        EffectPreset("telephone", "Telephone", "Voice changer",
            "Band-limited, slightly crunchy.",
            listOf(PresetFx("filter", mapOf("type" to 2f, "freq" to 1800f, "res" to 0.9f)),
                PresetFx("distortion", mapOf("drive" to 6f, "mix" to 0.3f, "type" to 0f)))),
        EffectPreset("harmonizer", "Harmonizer (+ major 3rd)", "Voice changer",
            "Adds a voice four semitones above, mixed with the original.",
            listOf(PresetFx("pitch_shift", mapOf("semitones" to 4f, "mix" to 0.5f)))),
        EffectPreset("stadium", "Stadium Space", "Space",
            "Large reverb with a slap echo.",
            listOf(PresetFx("reverb", mapOf("roomsize" to 0.9f, "wet" to 0.35f)),
                PresetFx("delay", mapOf("time_l" to 280f, "time_r" to 420f, "feedback" to 0.3f, "mix" to 0.2f))))
        ,
        EffectPreset("wide_double", "Wide Double", "Space",
            "Chorus doubling with extra stereo width.",
            listOf(PresetFx("chorus", mapOf("mix" to 0.45f)), PresetFx("stereo_width", mapOf("width" to 1.4f)))),
        EffectPreset("bright_air", "Bright Air", "Tone",
            "Harmonic exciter for presence and air.",
            listOf(PresetFx("exciter", mapOf("freq" to 4500f, "drive" to 12f, "amount" to 0.35f)))),
        EffectPreset("pump_tremolo", "Pulse Tremolo", "Modulation",
            "Rhythmic amplitude pulsing.",
            listOf(PresetFx("tremolo", mapOf("rate" to 6f, "depth" to 0.6f)))),
        EffectPreset("auto_pan", "Auto-Pan Sweep", "Modulation",
            "Slow left/right movement.",
            listOf(PresetFx("tremolo", mapOf("rate" to 0.5f, "depth" to 1f, "mode" to 1f))))
    )

    fun byId(id: String): EffectPreset? = all.firstOrNull { it.id == id }
    val categories: List<String> get() = all.map { it.category }.distinct()
}
