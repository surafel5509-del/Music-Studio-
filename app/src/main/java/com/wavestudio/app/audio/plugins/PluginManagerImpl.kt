package com.wavestudio.app.audio.plugins

import android.content.Context
import android.content.SharedPreferences
import com.wavestudio.app.domain.EffectType
import com.wavestudio.app.domain.PluginCategory
import com.wavestudio.app.domain.PluginInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginManagerImpl @Inject constructor(
    @ApplicationContext ctx: Context
) : PluginManager {

    private val prefs: SharedPreferences = ctx.getSharedPreferences("wavestudio_plugins", Context.MODE_PRIVATE)

    private val builtIn = listOf(
        PluginInfo("builtin.eq", "Parametric EQ", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
        PluginInfo("builtin.graphic_eq", "Graphic EQ", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
        PluginInfo("builtin.comp", "Compressor", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
        PluginInfo("builtin.limiter", "Brickwall Limiter", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
        PluginInfo("builtin.gate", "Noise Gate", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
        PluginInfo("builtin.deesser", "De-Esser", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
        PluginInfo("builtin.reverb", "Reverb", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
        PluginInfo("builtin.delay", "Delay", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
        PluginInfo("builtin.chorus", "Chorus", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
        PluginInfo("builtin.flanger", "Flanger", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
        PluginInfo("builtin.phaser", "Phaser", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
        PluginInfo("builtin.distortion", "Distortion", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
        PluginInfo("builtin.bitcrusher", "Bit Crusher", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
        PluginInfo("builtin.filter", "Filter", "WaveStudio", "1.0", PluginCategory.EFFECT, isBuiltIn = true),
        PluginInfo("builtin.tuner", "Tuner", "WaveStudio", "1.0", PluginCategory.UTILITY, isBuiltIn = true),
        PluginInfo("builtin.gain", "Gain Utility", "WaveStudio", "1.0", PluginCategory.UTILITY, isBuiltIn = true)
    )

    @Volatile private var installed: MutableList<PluginInfo> = mutableListOf()

    override fun getBuiltInPlugins(): List<PluginInfo> = builtIn

    override fun getInstalledPlugins(): List<PluginInfo> = synchronized(this) { installed.toList() }

    override suspend fun installPlugin(apkPath: String): Boolean {
        // Sandboxed plugin installation. Since VST3/AU are not supported on Android natively,
        // WaveStudio supports APK-provided native plugins loaded via a permission-restricted
        // ContentProvider contract. For this build, we simulate installation metadata only.
        val info = PluginInfo(
            id = "custom.${System.currentTimeMillis()}",
            name = apkPath.substringAfterLast('/').substringBeforeLast('.'),
            category = PluginCategory.EFFECT,
            path = apkPath,
            isBuiltIn = false
        )
        synchronized(this) { installed.add(info) }
        return true
    }

    override suspend fun removePlugin(id: String): Boolean {
        synchronized(this) { return installed.removeIf { it.id == id } }
    }

    override fun getEffectsForCategory(category: EffectType): List<PluginInfo> {
        return builtIn.filter { it.id.contains(category.name.lowercase()) ||
                (category == EffectType.EQ && it.id.contains("eq")) ||
                (category == EffectType.COMPRESSOR && it.id.contains("comp")) ||
                (category == EffectType.REVERB && it.id.contains("reverb")) ||
                (category == EffectType.DELAY && it.id.contains("delay"))
        }
    }

    override fun favorite(id: String) {
        val fav = getFavorites().toMutableSet().also { it.add(id) }
        prefs.edit().putStringSet("favorites", fav).apply()
    }

    override fun unfavorite(id: String) {
        val fav = getFavorites().toMutableSet().also { it.remove(id) }
        prefs.edit().putStringSet("favorites", fav).apply()
    }

    override fun getFavorites(): List<String> =
        prefs.getStringSet("favorites", emptySet())?.toList().orEmpty()
}
