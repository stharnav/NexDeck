package com.example.consolelauncher.bubble

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.example.consolelauncher.MainActivity
import com.example.consolelauncher.data.LauncherSettingsStore
import kotlin.math.abs

/**
 * The ACTION BUBBLE feature: a small floating bubble a [WindowManager] overlay keeps on top of
 * every screen once the "Display over other apps" permission is granted.
 *
 * The bubble can be dragged anywhere with a finger or a mouse; a tap without a real drag opens a
 * rectangular quick-actions panel anchored next to it:
 *
 *  - the system toggles (WIFI, BLUETOOTH, AIRPLANE MODE, DO NOT DISTURB). Non-system apps may not
 *    flip the radios themselves, so each row shows the live state and clicks open the matching
 *    system settings panel where Android wants the change made. DO NOT DISTURB flips directly once
 *    the notification policy access was granted, otherwise its rows opens that grant screen.
 *  - a HOME button into the launcher.
 *  - VOLUME and BRIGHTNESS sliders. Brightness needs the separate "Modify system settings"
 *    permission (WRITE_SETTINGS); until it is granted the slider snaps back and the first drag
 *    opens the permission page.
 *
 * The panel window takes focus while it is open, so a remote/D-pad can also drive it; BACK or
 * B (or tapping the bubble again) collapses it.
 */
class ActionBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var settingsStore: LauncherSettingsStore
    private lateinit var audio: AudioManager
    private lateinit var notifications: NotificationManager

    private var bubbleWindow: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panelWindow: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var statusRegisters = mutableMapOf<String, TextView>()
    private var viewRegisters = mutableMapOf<String, LinearLayout>()
    private var labelRegisters = mutableMapOf<String, TextView>()

    // Drag bookkeeping for the bubble itself.
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var dragStartLeft = 0
    private var dragStartTop = 0
    private var dragged = false

    /** BACK / gamepad B collapses the quick-actions panel from any focused row inside it. */
    private val backCatcher = View.OnKeyListener { _, keyCode, event ->
        if (event.action == KeyEvent.ACTION_DOWN &&
            (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B)
        ) {
            collapsePanel()
            true
        } else {
            false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settingsStore = LauncherSettingsStore(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Belt and braces: the launcher only starts this service after the permission is granted
        // (and stops it again right away when the permission goes away), because inserting an
        // overlay window without it is a hard crash.
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubbleWindow == null) showBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        collapsePanel()
        bubbleWindow?.let { windowManager.removeView(it) }
        bubbleWindow = null
        windowParamsCleanup()
        super.onDestroy()
    }

    private fun windowParamsCleanup() {
        bubbleParams = null
        statusRegisters.clear()
        viewRegisters.clear()
        labelRegisters.clear()
    }

    // ----------------------------------------------------------------------------- the bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val density = resources.displayMetrics.density

        val bubble = TextView(this).apply {
            text = "⚡"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = rounded(intArrayOf(ACCENT, ACCENT_SOFT), radiusDp = 28f)
            elevation = 6f * density
            contentDescription = "Action bubble"
            layoutParams = LinearLayout.LayoutParams(
                (BUBBLE_SIZE_DP * density).toInt(),
                (BUBBLE_SIZE_DP * density).toInt(),
            )
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        // The whole tap / drag behaviour lives in this listener: a real drag moves the window,
        // a plain tap (no movement beyond the touch slop) falls through to the click listener
        // that opens or closes the quick-actions panel.
        bubble.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    val params = bubbleParams ?: return@setOnTouchListener true
                    dragStartLeft = params.x
                    dragStartTop = params.y
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (dragged || abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        dragged = true
                        bubbleParams?.let { params ->
                            params.x = dragStartLeft + dx.toInt()
                            params.y = dragStartTop + dy.toInt()
                            windowManager.updateViewLayout(bubble, params)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged) view.performClick()
                    true
                }

                else -> true
            }
        }

        bubble.setOnClickListener {
            if (panelWindow != null) collapsePanel() else showPanel()
        }

        bubbleParams = overlayParams()
        windowManager.addView(bubble, bubbleParams)
        bubbleWindow = bubble
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        return WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            // The bubble starts parked near the middle of the right screen edge (the launcher runs
            // landscape only); afterwards the drag positions are absolute window coordinates.
            x = resources.displayMetrics.widthPixels -
                ((BUBBLE_SIZE_DP + EDGE_MARGIN_DP) * density).toInt()
            y = (resources.displayMetrics.heightPixels / 2 - BUBBLE_SIZE_DP * density / 2).toInt()
        }
    }

    // ------------------------------------------------------------------- the quick-actions panel

    private fun showPanel() {
        if (panelWindow != null) return
        val density = resources.displayMetrics.density

        // The back row is built first and pinned outside the scrollable area below, so it stays
        // visible even on screens too short for the whole panel.
        val backRow = actionButton("\u2190  BACK") { collapsePanel() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (10 * density).toInt(),
                0,
                (10 * density).toInt(),
                (4 * density).toInt(),
            )
        }
        root.setOnKeyListener(backCatcher)

        root.addView(
            TextView(this).apply {
                text = "QUICK ACTIONS"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.12f
                setTextColor(TEXT_SECONDARY)
            },
        )
        root.addView(spacer(10f))

        // The radios cannot be flipped by non-system apps on modern Android, so every toggle row
        // shows the live state and opens the matching system panel with the value mirrored over.
        root.addView(toggleRow("wifi", "WIFI") { openWifiSettings() })
        root.addView(toggleRow("bluetooth", "BLUETOOTH") { openBluetoothSettings() })
        root.addView(toggleRow("airplane", "AIRPLANE MODE") { openAirplaneSettings() })
        root.addView(toggleRow("dnd", "DO NOT DISTURB") { toggleDnd() })
        root.addView(spacer(6f))

        root.addView(actionButton("HOME") { openLauncher(MainActivity.TARGET_HOME) })
        root.addView(spacer(6f))

        val volumeBar = SeekBar(this).apply {
            max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            progress = try {
                audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            } catch (_: Exception) {
                0
            }
            tintTrack()
            setOnKeyListener(backCatcher)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    try {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                    } catch (_: Exception) {
                        // A stream that refuses the volume (e.g. actually muted by DND) simply
                        // keeps its old value.
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        root.addView(sliderRow("VOLUME", volumeBar))

        val brightnessBar = SeekBar(this).apply {
            max = BRIGHTNESS_SEEK_MAX
            progress = readBrightnessLevel(BRIGHTNESS_SEEK_MAX)
            tintTrack()
            setOnKeyListener(backCatcher)
            var promptedWriteSetting = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    if (!Settings.System.canWrite(this@ActionBubbleService)) {
                        // Snap back to the real system value and ask for the permission once per
                        // drag; the slider becomes live right after the user grants it.
                        bar.progress = readBrightnessLevel(BRIGHTNESS_SEEK_MAX)
                        if (!promptedWriteSetting) {
                            promptedWriteSetting = true
                            openSettingsScreen(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        }
                        return
                    }
                    try {
                        Settings.System.putInt(
                            contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            (BRIGHTNESS_MIN + value * (BRIGHTNESS_MAX - BRIGHTNESS_MIN) /
                                BRIGHTNESS_SEEK_MAX),
                        )
                    } catch (_: Exception) {
                        // Same story as the volume: a device that does not take the value keeps
                        // its old brightness.
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        root.addView(sliderRow("BRIGHTNESS", brightnessBar))

        refreshToggleStates()

        // The BACK row is pinned below the scroll: measure the scrollable content at the panel's
        // real width, then build the panel out of [pill back row][gap][scroll area] with explicit
        // heights. The whole panel therefore always fits between the screen edges - never a
        // clipped bottom row, and the back control is always on screen.
        val widthPx = (PANEL_WIDTH_DP * density).toInt()
        val contentW = widthPx - 2 * (8 * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(contentW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val backPx = (ROW_HEIGHT_DP * density).toInt()
        val backGapPx = (8 * density).toInt()
        val vPad = (8 * density).toInt()
        val screenH = resources.displayMetrics.heightPixels
        val screenMargin = (8 * density).toInt()
        val availableH = maxOf(backPx + backGapPx, screenH - 2 * screenMargin - 2 * vPad)
        val scrollH = minOf(root.measuredHeight, availableH - backPx - backGapPx).coerceAtLeast(backPx)
        val panelH = 2 * vPad + backPx + backGapPx + scrollH

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(
                root,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(intArrayOf(PANEL_BG, PANEL_BG), radiusDp = 18f, strokeColor = STROKE)
            setPadding(vPad, vPad, vPad, vPad)
        }
        panel.addView(
            backRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                backPx,
            ).apply { bottomMargin = backGapPx },
        )
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                scrollH,
            ),
        )

        panelParams = panelAnchorParams(panelH)
        windowManager.addView(panel, panelParams)
        panelWindow = panel
        // With the panel attached, put the D-pad focus onto the first toggle row.
        viewRegisters.values.firstOrNull()?.requestFocus()
    }

    private fun collapsePanel() {
        panelWindow?.let { windowManager.removeView(it) }
        panelWindow = null
        panelParams = null
        statusRegisters.clear()
        viewRegisters.clear()
        labelRegisters.clear()
    }

    /** Anchors the panel next to wherever the bubble currently sits; to its left when it fits. */
    private fun panelAnchorParams(panelH: Int): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        val bubble = bubbleParams!!
        val width = (PANEL_WIDTH_DP * density).toInt()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        var x = bubble.x - width - (8 * density).toInt()
        if (x < (4 * density).toInt()) {
            x = bubble.x + (BUBBLE_SIZE_DP * density).toInt() + (8 * density).toInt()
        }
        x = x.coerceIn(0, maxOf(0, screenW - width))
        val screenMargin = (8 * density).toInt()

        return WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            // The panel takes the focus while it is open so the remote/D-pad can drive it.
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            this.width = width
            height = panelH
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            // Keep the whole panel between the top and bottom screen edges.
            val minY = screenMargin
            val maxY = maxOf(minY, screenH - screenMargin - panelH)
            y = bubble.y.coerceIn(minY, maxY)
        }
    }

    // -------------------------------------------------------------------------------- row views

    private fun toggleRow(key: String, label: String, onClick: () -> Unit): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (ROW_HEIGHT_DP * density).toInt(),
            ).apply { bottomMargin = (6 * density).toInt() }
        }

        val text = TextView(this).apply {
            this.text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val status = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(TEXT_SECONDARY)
        }
        row.addView(text)
        row.addView(status)

        // The rows have to know their key so the shared styling can shade the ones whose feature
        // is currently on.
        viewRegisters[key] = row
        statusRegisters[key] = status
        labelRegisters[key] = text

        row.setOnClickListener {
            onClick()
            refreshToggleStates()
        }
        row.setOnKeyListener(backCatcher)
        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            styleToggleRow(key, hasFocus)
        }
        styleToggleRow(key, row.isFocused)
        return row
    }

    /**
     * Shared look of one toggle row, combining three layers:
     *
     *  - the feature is ON: the whole row carries a shaded accent wash, an accent stroke and an
     *    accent-colored status text, so the state is readable from across the room;
     *  - the row has the D-pad focus: the full accent highlight on top (like the launcher tiles);
     *  - otherwise: the neutral surface look.
     */
    private fun styleToggleRow(key: String, focused: Boolean) {
        val row = viewRegisters[key] ?: return
        val label = labelRegisters[key] ?: return
        val status = statusRegisters[key] ?: return

        val on = status.text == "ON"
        val background = when {
            focused -> ACCENT
            on -> TINT_ON
            else -> SURFACE
        }
        val stroke = if (focused || on) ACCENT else STROKE
        row.background = rounded(intArrayOf(background, background), radiusDp = 12f, strokeColor = stroke)

        if (focused) {
            // On the full accent focus highlight everything flips to the dark "on accent" ink.
            label.setTextColor(ON_ACCENT)
            status.setTextColor(ON_ACCENT)
        } else {
            label.setTextColor(if (on) ACCENT else TEXT_PRIMARY)
            status.setTextColor(if (on) ACCENT else TEXT_SECONDARY)
        }
    }

    private fun actionButton(label: String, onClick: () -> Unit): View {
        val density = resources.displayMetrics.density
        val button = TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (ROW_HEIGHT_DP * density).toInt(),
            )
        }
        applyRowStyle(button, focusedBg = ACCENT)
        button.setOnClickListener { onClick() }
        button.setOnKeyListener(backCatcher)
        return button
    }

    private fun sliderRow(label: String, bar: SeekBar): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (ROW_HEIGHT_DP * density).toInt(),
            ).apply { bottomMargin = (6 * density).toInt() }
        }

        row.addView(
            TextView(this).apply {
                text = label
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.06f
                setTextColor(TEXT_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(
                    (52 * density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            },
        )
        bar.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply { leftMargin = (6 * density).toInt() }
        row.addView(bar)
        row.setOnKeyListener(backCatcher)
        return row
    }

    /** Shared focus styling: every row highlights like the launcher tiles when it gets the focus. */
    private fun applyRowStyle(view: View, focusedBg: Int = ACCENT) {
        view.isFocusable = true
        fun update(focused: Boolean) {
            val bg = if (focused) focusedBg else SURFACE
            view.background = rounded(
                intArrayOf(bg, bg),
                radiusDp = 12f,
                strokeColor = if (focused) ACCENT else STROKE,
            )
        }
        view.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> update(hasFocus) }
        update(false)
    }

    private fun SeekBar.tintTrack() {
        thumbTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(STROKE)
    }

    private fun spacer(heightDp: Float): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (heightDp * density).toInt(),
            )
        }
    }

    /**
     * Reads the live radio states into the toggle rows. Every read can throw or lie on a given
     * build, so a failure degrades to a neutral dash instead of taking the panel down.
     */
    private fun refreshToggleStates() {
        setStatus("wifi", readWifiState())
        setStatus("bluetooth", readBluetoothState())
        setStatus("airplane", readAirplaneState())
        setStatus("dnd", readDndState() ?: "—")

        // Re-style all rows together: a row whose feature just turned on picks up its accent wash.
        viewRegisters.keys.forEach { key -> styleToggleRow(key, viewRegisters[key]?.isFocused == true) }
    }

    private fun setStatus(key: String, text: String) {
        statusRegisters[key]?.text = text
    }

    private fun readWifiState(): String = try {
        val wifi = getSystemService(WifiManager::class.java)
        if (wifi?.isWifiEnabled == true) "ON" else "OFF"
    } catch (_: Exception) {
        "—"
    }

    private fun readBluetoothState(): String = try {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        when (adapter?.isEnabled) {
            true -> "ON"
            false -> "OFF"
            null -> "—"
        }
    } catch (_: Exception) {
        "—"
    }

    private fun readAirplaneState(): String = try {
        if (Settings.Global.getInt(
                contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0,
            ) == 1
        ) {
            "ON"
        } else {
            "OFF"
        }
    } catch (_: Exception) {
        "—"
    }

    private fun readDndState(): String? = try {
        if (notifications.currentInterruptionFilter !in setOf(
                NotificationManager.INTERRUPTION_FILTER_ALL,
                NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
            )
        ) {
            "ON"
        } else {
            "OFF"
        }
    } catch (_: Exception) {
        null
    }

    private fun readBrightnessLevel(fallback: Int): Int = try {
        val level = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        ((level.coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX) - BRIGHTNESS_MIN) * BRIGHTNESS_SEEK_MAX /
            (BRIGHTNESS_MAX - BRIGHTNESS_MIN)).coerceIn(0, BRIGHTNESS_SEEK_MAX)
    } catch (_: Exception) {
        fallback
    }

    // ----------------------------------------------------------------------- system interactions

    private fun openWifiSettings() =
        openSettingsScreen(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_WIFI
            } else {
                null
            },
            fallback = Settings.ACTION_WIFI_SETTINGS,
        )

    private fun openBluetoothSettings() =
        // There is no dedicated Bluetooth settings panel; the plain Bluetooth settings screen it is.
        openSettingsScreen(Settings.ACTION_BLUETOOTH_SETTINGS)

    /**
     * Opens the dedicated airplane-mode settings screen. The Internet connectivity panel was a
     * poor fit: builds that just show a network popup there leave the user in the wrong dialog.
     * The panel stays as a fallback for devices without the airplane screen.
     */
    private fun openAirplaneSettings() =
        openSettingsScreen(
            Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            } else {
                null
            },
        )

    /** DND flips directly once its policy access is granted, otherwise that page comes up. */
    private fun toggleDnd() {
        if (notifications.isNotificationPolicyAccessGranted) {
            val target =
                if (readDndState() == "ON") {
                    NotificationManager.INTERRUPTION_FILTER_ALL
                } else {
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY
                }
            try {
                notifications.setInterruptionFilter(target)
            } catch (_: Exception) {
                // Some builds refuse a filter change; the row keeps showing the real state.
            }
        } else {
            openSettingsScreen(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }
    }

    private fun openSettingsScreen(action: String?, fallback: String? = null) {
        collapsePanel()
        if (action == null) {
            if (fallback != null) openSettingsScreen(fallback)
            return
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            if (fallback != null && fallback != action) openSettingsScreen(fallback)
        }
    }

    private fun openLauncher(target: String?) {
        collapsePanel()
        val launch = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (target != null) launch.putExtra(MainActivity.EXTRA_TARGET_SCREEN, target)
        try {
            startActivity(launch)
        } catch (_: Exception) {
            // The activity is a plain HOME activity; there is nothing it can fail with that we
            // could surface from the bubble.
        }
    }

    private fun rounded(colors: IntArray, radiusDp: Float, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TL_BR
            setColors(colors)
            cornerRadius = radiusDp * resources.displayMetrics.density
            if (strokeColor != 0) {
                setStroke((resources.displayMetrics.density * 1).toInt(), strokeColor)
            }
        }

    private companion object {
        const val BUBBLE_SIZE_DP = 56f
        const val EDGE_MARGIN_DP = 16f
        const val PANEL_WIDTH_DP = 248f
        const val ROW_HEIGHT_DP = 44f

        const val BRIGHTNESS_SEEK_MAX = 100
        const val BRIGHTNESS_MIN = 10
        const val BRIGHTNESS_MAX = 255

        const val ACCENT = 0xFF4FA3FF.toInt()
        const val ACCENT_SOFT = 0xFF66C0F4.toInt()
        const val SURFACE = 0xFF22262E.toInt()
        /** The surface blended ~25% into the accent: the "feature is on" shade of the toggle rows. */
        const val TINT_ON = 0xFF2D4562.toInt()
        const val ON_ACCENT = 0xFF06101F.toInt()
        const val PANEL_BG = 0xE6161A20.toInt()
        const val STROKE = 0xFF343A45.toInt()
        const val TEXT_PRIMARY = 0xFFE8EBF0.toInt()
        const val TEXT_SECONDARY = 0xFF9AA3B0.toInt()
    }
}
