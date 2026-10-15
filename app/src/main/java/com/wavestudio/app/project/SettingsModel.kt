package com.wavestudio.app.project

enum class ThemeMode { SYSTEM, LIGHT, DARK;
    companion object { fun parse(s: String?): ThemeMode = values().firstOrNull { it.name == s } ?: SYSTEM }
}

class AccentOption(val name: String, val argb: Long)

/**
 * User preferences (pure data, no Android dependency so it is unit-tested on the JVM).
 * Every field is wired to real behaviour: theme, screen-on, the recording input chain and export defaults.
 */
data class AppSettingsData(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentIndex: Int = 0,
    val dynamicColor: Boolean = false,
    val keepScreenOn: Boolean = true,
    /** Recording file format: 16 / 24 (integer PCM) or 32 (float, bit-exact copy of the input). */
    val recordBitDepth: Int = 24,
    /** Input trim in dB (-24..+24), applied before metering, monitoring and the recorder. */
    val inputGainDb: Float = 0f,
    /** 0 = off, else a 2nd-order low-cut at this frequency (removes rumble / handling noise). */
    val inputLowCutHz: Int = 0,
    val removeDc: Boolean = true,
    val exportFlac: Boolean = false,
    val exportBitDepth: Int = 24,
    val exportSampleRate: Int = 48000
) {
    val accent: AccentOption get() = ACCENTS[accentIndex.coerceIn(0, ACCENTS.size - 1)]

    fun sanitized(): AppSettingsData {
        val g = if (inputGainDb.isNaN()) 0f else inputGainDb.coerceIn(-24f, 24f)
        val exBits = exportBitDepth.takeIf { it == 16 || it == 24 || it == 32 } ?: 24
        return copy(
            accentIndex = accentIndex.coerceIn(0, ACCENTS.size - 1),
            recordBitDepth = recordBitDepth.takeIf { it == 16 || it == 24 || it == 32 } ?: 24,
            inputGainDb = Math.round(g * 2f) / 2f,                         // 0.5 dB steps
            inputLowCutHz = LOW_CUTS.minByOrNull { Math.abs(it - inputLowCutHz) } ?: 0,
            exportBitDepth = if (exportFlac && exBits == 32) 24 else exBits,   // FLAC has no 32-bit
            exportSampleRate = exportSampleRate.takeIf { it == 44100 || it == 48000 || it == 96000 } ?: 48000
        )
    }

    companion object {
        val ACCENTS = listOf(
            AccentOption("Blue", 0xFF5DA9FF), AccentOption("Green", 0xFF43E97B), AccentOption("Orange", 0xFFFFA24A),
            AccentOption("Pink", 0xFFFF6FB5), AccentOption("Purple", 0xFFB48CFF), AccentOption("Teal", 0xFF3CCFCF)
        )
        val LOW_CUTS = listOf(0, 40, 80, 120)
    }
}
