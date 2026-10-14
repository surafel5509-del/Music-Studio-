package com.wavestudio.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wavestudio.app.R
import com.wavestudio.app.WaveStudioApp
import com.wavestudio.app.engine.AudioEngineController
import com.wavestudio.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service keeps the audio engine alive across configuration changes
 * and while the user is recording/playback in the background.
 */
@AndroidEntryPoint
class AudioEngineService : Service() {

    @Inject lateinit var engine: AudioEngineController

    override fun onCreate() {
        super.onCreate()
        engine.start()
        startForeground(WaveStudioApp.NOTIF_ENGINE_ID, buildNotification("Idle"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> engine.play()
            ACTION_PAUSE -> engine.pause()
            ACTION_STOP -> engine.stopTransport()
            ACTION_RECORD -> engine.startRecording()
            ACTION_STOP_RECORD -> engine.stopRecording()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, WaveStudioApp.CHANNEL_ENGINE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_PLAY = "com.wavestudio.app.PLAY"
        const val ACTION_PAUSE = "com.wavestudio.app.PAUSE"
        const val ACTION_STOP = "com.wavestudio.app.STOP"
        const val ACTION_RECORD = "com.wavestudio.app.RECORD"
        const val ACTION_STOP_RECORD = "com.wavestudio.app.STOP_RECORD"
    }
}
