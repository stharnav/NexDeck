package com.example.consolelauncher

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.consolelauncher.ui.LauncherRoot
import com.example.consolelauncher.ui.theme.ConsoleLauncherTheme

class MainActivity : ComponentActivity() {

    /**
     * Bumped every time the launcher comes back to the foreground so that the app list and the
     * launch history are refreshed after the user came back from another application.
     */
    private val refreshKey = mutableIntStateOf(0)
    private var hasResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        enterFullscreen()
        setContent {
            ConsoleLauncherTheme {
                LauncherRoot(refreshKey = refreshKey.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enterFullscreen()
        if (hasResumed) {
            refreshKey.intValue++
        } else {
            hasResumed = true
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    /**
     * Hides the system status bar and the navigation bar so that the launcher fills the whole
     * screen. A swipe from the edge brings the bars back temporarily.
     */
    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
