package com.swipeyt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.swipeyt.ui.SwipeYtApp
import com.swipeyt.ui.theme.SwipeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SwipeTheme {
                SwipeYtApp(appContainer)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        appContainer.player.pause()
    }
}
