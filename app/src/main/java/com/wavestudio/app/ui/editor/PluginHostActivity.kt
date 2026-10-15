package com.wavestudio.app.ui.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.wavestudio.app.ui.effects.EffectsScreen
import com.wavestudio.app.ui.theme.WaveStudioTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Standalone host activity for plugin UI — provides a sandboxed surface for
 * third-party plugin UI (loaded via a dedicated SurfaceView / Compose view).
 * For built-in plugins this re-uses the Effects screen with a specific plugin id.
 */
@AndroidEntryPoint
class PluginHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trackId = intent.getIntExtra("trackId", 0)
        setContent {
            WaveStudioTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EffectsScreen(trackId = trackId, onBack = { finish() })
                }
            }
        }
    }
}
