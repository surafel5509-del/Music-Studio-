package com.wavestudio.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.wavestudio.app.R
import com.wavestudio.app.WaveStudioApp
import com.wavestudio.app.audio.AudioIO
import com.wavestudio.app.domain.Project
import com.wavestudio.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class ExportService : Service() {

    @Inject lateinit var audioIO: AudioIO

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val outPath = intent?.getStringExtra(EXTRA_OUT_PATH) ?: return START_NOT_STICKY
        val format = intent?.getStringExtra(EXTRA_FORMAT) ?: "wav"
        val project = Project(
            name = intent?.getStringExtra(EXTRA_PROJECT_NAME) ?: "export",
            sampleRate = intent?.getFloatExtra(EXTRA_SR, 48000f) ?: 48000f,
            channels = intent?.getIntExtra(EXTRA_CH, 2) ?: 2
        )
        startForeground(WaveStudioApp.NOTIF_EXPORT_ID, buildNotification(getString(R.string.exporting)))
        scope.launch {
            val out = File(outPath)
            val ok = when (format) {
                "mp3", "aac", "m4a" -> audioIO.exportCompressed(project, out, "audio/mp4a-latm", 320_000) {
                    updateNotification(getString(R.string.exporting) + " ${(it*100).toInt()}%")
                }
                else -> audioIO.exportWav(project, out, 24) {
                    updateNotification(getString(R.string.exporting) + " ${(it*100).toInt()}%")
                }
            }
            updateNotification(if (ok) getString(R.string.export_done) else getString(R.string.export_failed))
            if (ok) shareExport(out)
            delay(2000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun shareExport(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(share, getString(R.string.share_export)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooser)
        } catch (_: Throwable) {}
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, WaveStudioApp.CHANNEL_EXPORT)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_export)
            .setContentIntent(pi)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(WaveStudioApp.NOTIF_EXPORT_ID, buildNotification(text))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OUT_PATH = "out_path"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_PROJECT_NAME = "pname"
        const val EXTRA_SR = "sr"
        const val EXTRA_CH = "ch"
    }
}
