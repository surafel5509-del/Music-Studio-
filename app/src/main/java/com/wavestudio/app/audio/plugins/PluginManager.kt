package com.wavestudio.app.audio.plugins

import com.wavestudio.app.domain.EffectType
import com.wavestudio.app.domain.PluginCategory
import com.wavestudio.app.domain.PluginInfo

interface PluginManager {
    fun getBuiltInPlugins(): List<PluginInfo>
    fun getInstalledPlugins(): List<PluginInfo>
    suspend fun installPlugin(apkPath: String): Boolean
    suspend fun removePlugin(id: String): Boolean
    fun getEffectsForCategory(category: EffectType): List<PluginInfo>
    fun favorite(id: String)
    fun unfavorite(id: String)
    fun getFavorites(): List<String>
}
