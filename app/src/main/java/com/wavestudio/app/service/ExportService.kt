package com.wavestudio.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wavestudio.app.R
import com.wavestudio.app.WaveStudioApp
import com.wavestudio.app.session.ExportState
import com.wavestudio.app.session.ProjectSession
import com.wavestudio.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive and shows progress while [ProjectSession.export] runs. The rendering itself is done by
 * the native offline exporter started by the session; this service only watches it and stops when it ends.
 */
@AndroidEntryPoint
class ExportService : Service() {
    @Inject lateinit var session: ProjectSession
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification("Exporting…", 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(WaveStudioApp.NOTIF_EXPORT_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(WaveStudioApp.NOTIF_EXPORT_ID, n)
        scope.launch {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            while (session.exportState.value is ExportState.Running) {
                val pct = (session.exportProgress() * 100).toInt().coerceIn(0, 100)
                nm.notify(WaveStudioApp.NOTIF_EXPORT_ID, notification("Exporting… $pct%", pct))
                delay(500)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String, pct: Int): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, WaveStudioApp.CHANNEL_EXPORT)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_export)
            .setContentIntent(pi)
            .setOngoing(true)
            .setProgress(100, pct, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, ExportService::class.java)) } catch (_: Exception) {}
        }
    }
}
