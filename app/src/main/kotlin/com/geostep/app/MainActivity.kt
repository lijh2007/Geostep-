package com.geostep.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.geostep.app.ui.HomeScreen
import com.geostep.app.ui.SplashOverlay
import com.geostep.app.ui.theme.GeostepTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GeostepTheme {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                    var splashDone by remember { mutableStateOf(false) }
                    HomeScreen()
                    AnimatedVisibility(visible = !splashDone, exit = fadeOut(androidx.compose.animation.core.tween(220))) {
                        SplashOverlay(onFinished = { splashDone = true })
                    }
                }
            }
        }
    }
}
