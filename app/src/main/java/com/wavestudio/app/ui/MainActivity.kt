package com.wavestudio.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.wavestudio.app.engine.AudioEngineController
import com.wavestudio.app.service.AudioEngineService
import com.wavestudio.app.ui.navigation.WaveStudioNavGraph
import com.wavestudio.app.ui.theme.WaveStudioTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var engine: AudioEngineController

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        // start engine regardless; recording features will remain disabled if perms denied
        startEngineAndService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
            required.add(Manifest.permission.READ_MEDIA_AUDIO)
            required.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            required.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (required.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPerms.launch(required.toTypedArray())
        } else {
            startEngineAndService()
        }

        setContent {
            WaveStudioTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()
                    WaveStudioNavGraph(navController = navController)
                }
            }
        }

        // Handle VIEW intent for opening audio files
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = intent?.data
        if (uri != null && (intent.action == Intent.ACTION_VIEW)) {
            lifecycleScope.launch {
                // Pass URI to editor via navigation arg (delegated to ProjectsViewModel)
            }
        }
    }

    private fun startEngineAndService() {
        val svc = Intent(this, AudioEngineService::class.java)
        ContextCompat.startForegroundService(this, svc)
        engine.start()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
