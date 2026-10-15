package com.wavestudio.app.settings

import android.content.Context
import com.wavestudio.app.project.AppSettingsData
import com.wavestudio.app.project.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** SharedPreferences-backed preferences exposed as a [StateFlow]. Local only: nothing leaves the device. */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext ctx: Context) {
    private val prefs = ctx.getSharedPreferences("wavestudio_settings", Context.MODE_PRIVATE)
    private val _data = MutableStateFlow(load())
    val data: StateFlow<AppSettingsData> = _data.asStateFlow()

    val value: AppSettingsData get() = _data.value

    private fun load(): AppSettingsData {
        val d = AppSettingsData()
        return AppSettingsData(
            themeMode = ThemeMode.parse(prefs.getString("theme", null)),
            accentIndex = prefs.getInt("accent", d.accentIndex),
            dynamicColor = prefs.getBoolean("dynamic", d.dynamicColor),
            keepScreenOn = prefs.getBoolean("keep_screen_on", d.keepScreenOn),
            recordBitDepth = prefs.getInt("rec_bits", d.recordBitDepth),
            inputGainDb = prefs.getFloat("in_gain_db", d.inputGainDb),
            inputLowCutHz = prefs.getInt("in_lowcut", d.inputLowCutHz),
            removeDc = prefs.getBoolean("in_dc", d.removeDc),
            exportFlac = prefs.getBoolean("exp_flac", d.exportFlac),
            exportBitDepth = prefs.getInt("exp_bits", d.exportBitDepth),
            exportSampleRate = prefs.getInt("exp_rate", d.exportSampleRate)
        ).sanitized()
    }

    @Synchronized
    fun update(change: (AppSettingsData) -> AppSettingsData) {
        val n = change(_data.value).sanitized()
        _data.value = n
        prefs.edit()
            .putString("theme", n.themeMode.name).putInt("accent", n.accentIndex).putBoolean("dynamic", n.dynamicColor)
            .putBoolean("keep_screen_on", n.keepScreenOn).putInt("rec_bits", n.recordBitDepth)
            .putFloat("in_gain_db", n.inputGainDb).putInt("in_lowcut", n.inputLowCutHz).putBoolean("in_dc", n.removeDc)
            .putBoolean("exp_flac", n.exportFlac).putInt("exp_bits", n.exportBitDepth).putInt("exp_rate", n.exportSampleRate)
            .apply()
    }
}
