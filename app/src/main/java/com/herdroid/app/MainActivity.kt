package com.herdroid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.herdroid.app.ui.HerDroidApp
import com.herdroid.app.ui.renderer.HermesRendererHost
import com.herdroid.app.ui.renderer.hasHermesRenderer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val rendererAvailable = hasHermesRenderer(this)
        setContent {
            if (rendererAvailable) {
                HermesRendererHost(Modifier.fillMaxSize())
            } else {
                HerDroidApp()
            }
        }
    }
}
