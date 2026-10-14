package com.wavestudio.app.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wavestudio.app.R
import com.wavestudio.app.WaveStudioApp
import com.wavestudio.app.engine.AudioEngineController
import com.wavestudio.app.engine.TransportState
import com.wavestudio.app.session.ProjectSession
import com.wavestudio.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that exists only while audio is playing or recording, so the system does not kill the
 * process mid-take when the app is in the background. It also owns audio focus (another app starting playback or a
 * phone call pauses the transport and commits any take being recorded) and reacts to headphones being unplugged.
 * It does not render audio itself: the engine lives in the native layer.
 */
@AndroidEntryPoint
class AudioEngineService : Service() {

    @Inject lateinit var engine: AudioEngineController
    @Inject lateinit var session: ProjectSession

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watcher: Job? = null
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) session.onAudioInterruption()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> session.onAudioInterruption()
            else -> {}   // regained: the user decides when to resume
        }
    }

    override fun onCreate() {
        super.onCreate()
        goForeground("Audio engine running")
        requestFocus()
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        noisyRegistered = true
        watcher = scope.launch {
            var idleTicks = 0
            engine.transportState.collect { state ->
                if (state == TransportState.PLAYING || state == TransportState.RECORDING) idleTicks = 0
                else {
                    val mine = ++idleTicks
                    delay(3000)
                    if (mine == idleTicks && engine.transportState.value != TransportState.PLAYING &&
                        engine.transportState.value != TransportState.RECORDING) stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { session.stop(); stopSelf() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        if (noisyRegistered) runCatching { unregisterReceiver(noisyReceiver) }
        abandonFocus()
        super.onDestroy()
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            // Android 14 refuses a microphone-type service without the runtime permission.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(WaveStudioApp.NOTIF_ENGINE_ID, n, type)
        } else startForeground(WaveStudioApp.NOTIF_ENGINE_ID, n)
    }

    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        }
    }

    private fun abandonFocus() {
        val am = getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AudioEngineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, WaveStudioApp.CHANNEL_ENGINE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.wavestudio.app.STOP"

        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, AudioEngineService::class.java)) } catch (_: Exception) {}
        }
    }
}
