package com.wavestudio.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WaveStudioApp : Application() {

    companion object {
        const val CHANNEL_ENGINE = "wavestudio_engine"
        const val CHANNEL_EXPORT = "wavestudio_export"
        const val NOTIF_ENGINE_ID = 1001
        const val NOTIF_EXPORT_ID = 1002
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val engine = NotificationChannel(
                CHANNEL_ENGINE,
                getString(R.string.channel_engine),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_engine_desc)
                setShowBadge(false)
            }
            val export = NotificationChannel(
                CHANNEL_EXPORT,
                getString(R.string.channel_export),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_export_desc)
            }
            nm.createNotificationChannels(listOf(engine, export))
        }
    }
}
