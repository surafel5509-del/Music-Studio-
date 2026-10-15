package com.wavestudio.app.project

/** Display names and browser categories for the native effect types (the engine only knows the type ids). */
object EffectCatalog {
    private val titles = mapOf(
        "gain" to "Gain / Phase", "eq" to "Parametric EQ", "filter" to "Filter", "compressor" to "Compressor", "limiter" to "Limiter",
        "gate" to "Noise Gate", "deesser" to "De-esser", "reverb" to "Reverb", "delay" to "Stereo Delay", "chorus" to "Chorus",
        "flanger" to "Flanger", "phaser" to "Phaser", "distortion" to "Distortion", "bitcrusher" to "Bitcrusher",
        "stereo_width" to "Stereo Width", "pitch_shift" to "Pitch Shifter", "autotune" to "Auto-Tune", "voice_changer" to "Voice Changer",
        "denoise" to "Noise Reduction", "tremolo" to "Tremolo / Auto-Pan", "exciter" to "Exciter"
    )
    private val categories = linkedMapOf(
        "Voice" to listOf("autotune", "voice_changer", "pitch_shift", "denoise", "deesser"),
        "Dynamics" to listOf("compressor", "limiter", "gate"),
        "EQ & filter" to listOf("eq", "filter"),
        "Space & time" to listOf("reverb", "delay"),
        "Modulation" to listOf("chorus", "flanger", "phaser", "tremolo"),
        "Colour" to listOf("distortion", "bitcrusher", "exciter"),
        "Utility" to listOf("gain", "stereo_width")
    )

    fun title(type: String): String = titles[type] ?: type.replace('_', ' ').replaceFirstChar { it.uppercase() }
    fun category(type: String): String = categories.entries.firstOrNull { type in it.value }?.key ?: "Other"

    /** Types grouped by category in a fixed, sensible order; types unknown to this catalogue land in "Other". */
    fun grouped(types: List<String>): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        for ((cat, members) in categories) {
            val present = members.filter { it in types }
            if (present.isNotEmpty()) out.add(cat to present)
        }
        val known = categories.values.flatten().toSet()
        val other = types.filter { it !in known }
        if (other.isNotEmpty()) out.add("Other" to other)
        return out
    }
}
