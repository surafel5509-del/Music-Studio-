package com.wavestudio.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.wavestudio.app.engine.AudioEngineController
import com.wavestudio.app.engine.TransportState
import com.wavestudio.app.session.ProjectSession
import com.wavestudio.app.ui.navigation.WaveStudioNavGraph
import com.wavestudio.app.ui.theme.WaveStudioTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var engine: AudioEngineController
    @Inject lateinit var session: ProjectSession

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // The engine always starts; the microphone stream is only opened when RECORD_AUDIO was granted.
        startEngine()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Audio files are imported through the system file picker (no storage permission needed).
        if (required.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPerms.launch(required.toTypedArray())
        } else {
            startEngine()
        }

        setContent {
            WaveStudioTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()
                    WaveStudioNavGraph(navController = navController)
                }
            }
        }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startEngine() {
        engine.start(wantInput = hasMic())
    }

    override fun onStart() {
        super.onStart()
        engine.setUiVisible(true)
        // The device is closed while the app is in the background and idle (battery); reopen it now.
        if (!engine.isStarted) startEngine()
    }

    override fun onStop() {
        super.onStop()
        engine.setUiVisible(false)
        // Make sure nothing is lost if the system kills us in the background.
        Thread { session.saveBlocking() }.start()
        val st = engine.transportState.value
        if (st != TransportState.PLAYING && st != TransportState.RECORDING) engine.stop()
    }
}
