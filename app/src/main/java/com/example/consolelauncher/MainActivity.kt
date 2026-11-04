package com.example.consolelauncher

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.consolelauncher.data.LauncherSettingsStore
import com.example.consolelauncher.ui.LauncherRoot
import com.example.consolelauncher.ui.theme.ConsoleLauncherTheme
import com.example.consolelauncher.ui.theme.accentOptionById
import com.example.consolelauncher.ui.theme.setAccentOption

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * Extra carried by the ACTION BUBBLE shortcut intents so the launcher opens straight on a
         * specific screen instead of restoring wherever it was left.
         */
        const val EXTRA_TARGET_SCREEN = "console_launcher.target_screen"
        const val TARGET_HOME = "home"
        const val TARGET_APPS = "apps"
    }

    /**
     * Bumped every time the launcher comes back to the foreground so that the app list and the
     * launch history are refreshed after the user came back from another application.
     */
    private val refreshKey = mutableIntStateOf(0)

    /** A bubble shortcut target waiting to be consumed by [LauncherRoot], null once consumed. */
    private val pendingTarget = mutableStateOf<String?>(null)
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
        consumeTarget(intent)
        // Restore the persisted accent before the first frame composes so the theme does not
        // flash the default blue after a process death.
        setAccentOption(
            accentOptionById(LauncherSettingsStore(this).accentColor()),
        )
        setContent {
            ConsoleLauncherTheme {
                LauncherRoot(
                    refreshKey = refreshKey.intValue,
                    pendingTarget = pendingTarget.value,
                    onTargetConsumed = { pendingTarget.value = null },
                )
            }
        }
    }

    /**
     * The launcher is `singleTask`, so bubble shortcut intents land here when the launcher is
     * already running; the raw intent carries the target screen for [LauncherRoot].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeTarget(intent)
    }

    private fun consumeTarget(intent: Intent?) {
        val target = intent?.getStringExtra(EXTRA_TARGET_SCREEN) ?: return
        pendingTarget.value = target
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
