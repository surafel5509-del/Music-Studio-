package com.wavestudio.app.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wavestudio_settings")

object PrefKeys {
    val DARK_MODE = booleanPreferencesKey("dark_mode")
    val CUSTOM_COLORS = booleanPreferencesKey("custom_colors")
    val SAMPLE_RATE = intPreferencesKey("sample_rate")
    val BUFFER_SIZE = intPreferencesKey("buffer_size")
    val SHOW_WAVEFORMS = booleanPreferencesKey("show_waveforms")
    val METRONOME = booleanPreferencesKey("metronome")
    val AUTO_SAVE = booleanPreferencesKey("auto_save")
}

data class AppSettings(
    val darkMode: Boolean = true,
    val customColors: Boolean = false,
    val sampleRate: Int = 48000,
    val bufferSize: Int = 256,
    val showWaveforms: Boolean = true,
    val metronome: Boolean = false,
    val autoSave: Boolean = true
)

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            darkMode = p[PrefKeys.DARK_MODE] ?: true,
            customColors = p[PrefKeys.CUSTOM_COLORS] ?: false,
            sampleRate = p[PrefKeys.SAMPLE_RATE] ?: 48000,
            bufferSize = p[PrefKeys.BUFFER_SIZE] ?: 256,
            showWaveforms = p[PrefKeys.SHOW_WAVEFORMS] ?: true,
            metronome = p[PrefKeys.METRONOME] ?: false,
            autoSave = p[PrefKeys.AUTO_SAVE] ?: true
        )
    }
    suspend fun setDarkMode(v: Boolean) = context.dataStore.edit { it[PrefKeys.DARK_MODE] = v }
    suspend fun setCustomColors(v: Boolean) = context.dataStore.edit { it[PrefKeys.CUSTOM_COLORS] = v }
    suspend fun setSampleRate(v: Int) = context.dataStore.edit { it[PrefKeys.SAMPLE_RATE] = v }
    suspend fun setBufferSize(v: Int) = context.dataStore.edit { it[PrefKeys.BUFFER_SIZE] = v }
    suspend fun setShowWaveforms(v: Boolean) = context.dataStore.edit { it[PrefKeys.SHOW_WAVEFORMS] = v }
    suspend fun setMetronome(v: Boolean) = context.dataStore.edit { it[PrefKeys.METRONOME] = v }
    suspend fun setAutoSave(v: Boolean) = context.dataStore.edit { it[PrefKeys.AUTO_SAVE] = v }
}
