package com.example.consolelauncher.bubble

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.example.consolelauncher.MainActivity
import com.example.consolelauncher.R
import com.example.consolelauncher.data.LauncherSettingsStore
import com.example.consolelauncher.ui.theme.accentOptionById
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The ACTION BUBBLE feature: a small floating bubble a [WindowManager] overlay keeps on top of
 * every screen once the "Display over other apps" permission is granted.
 *
 * The bubble can be dragged anywhere with a finger or a mouse; a tap without a real drag opens a
 * rectangular quick-actions panel anchored next to it:
 *
 *  - a 2 by 2 icon grid of the system toggles: Wi-Fi and Bluetooth on the top row, Airplane mode
 *    and Do Not Disturb on the second. Non-system apps may not flip the radios themselves, so
 *    each tile shows the live state and clicks open the matching system settings panel where
 *    Android wants the change made. DO NOT DISTURB flips directly once the notification policy
 *    access was granted, otherwise its tile opens that grant screen.
 *  - VOLUME and BRIGHTNESS sliders. Brightness needs the separate "Modify system settings"
 *    permission (WRITE_SETTINGS); until it is granted the slider snaps back and the first drag
 *    opens the permission page.
 *  - a BACK | HOME navigation row at the bottom, BACK focused on open.
 *
 * The panel window takes focus while it is open, so a remote/D-pad can also drive it; BACK or
 * B (or tapping the bubble again) collapses it.
 */
class ActionBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var settingsStore: LauncherSettingsStore
    private lateinit var audio: AudioManager
    private lateinit var notifications: NotificationManager

    // The accent colors come from the THEMES tab of the settings (see [applyAccentColors]);
    // they are initialized with the default blue so the fields are never 0.
    private var accentColor = 0xFF4FA3FF.toInt()
    private var accentSoftColor = 0xFF66C0F4.toInt()

    /** The surface blended ~25% into the accent: the "feature is on" shade of the toggle rows. */
    private var tintOnColor = 0xFF2D4562.toInt()

    private var bubbleWindow: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panelWindow: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var statusRegisters = mutableMapOf<String, TextView>()
    private var viewRegisters = mutableMapOf<String, LinearLayout>()
    private var iconRegisters = mutableMapOf<String, View>()

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
        applyAccentColors()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * Applies the accent the user picked on the THEMES tab: the radio tiles, sliders, buttons and
     * the bubble itself all rebuild with these values. Windows built before a change (the bubble
     * head) re-apply when the launcher restarts this service.
     */
    private fun applyAccentColors() {
        val option = accentOptionById(settingsStore.accentColor())
        accentColor = option.accent.toArgb()
        accentSoftColor = option.accentSoft.toArgb()
        tintOnColor = blendIntoSurface(accentColor)
    }

    /** The surface blended [fraction]-deep with [accent], the recipe of the old TINT_ON. */
    private fun blendIntoSurface(accent: Int, fraction: Float = 0.25f): Int {
        fun channel(base: Int, overlay: Int): Int = (base + (overlay - base) * fraction).roundToInt()
        return Color.argb(
            Color.alpha(SURFACE),
            channel(Color.red(SURFACE), Color.red(accent)),
            channel(Color.green(SURFACE), Color.green(accent)),
            channel(Color.blue(SURFACE), Color.blue(accent)),
        )
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
        iconRegisters.clear()
    }

    // ----------------------------------------------------------------------------- the bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val density = resources.displayMetrics.density

        // The bubble head now wears the provided quick-bubble icon on its accent circle.
        // FIT_CENTER keeps the oversized source PNG inside the circle; the padding leaves an
        // even accent ring around the glyph.
        val bubble = ImageView(this).apply {
            setImageResource(R.drawable.quickbubble)
            imageTintList = ColorStateList.valueOf(Color.BLACK)
            background = rounded(intArrayOf(accentColor, accentSoftColor), radiusDp = 20f)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(
                (9 * resources.displayMetrics.density).toInt(),
                (9 * resources.displayMetrics.density).toInt(),
                (9 * resources.displayMetrics.density).toInt(),
                (9 * resources.displayMetrics.density).toInt(),
            )
            elevation = 6f * resources.displayMetrics.density
            contentDescription = "Action bubble"
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
        val size = (BUBBLE_SIZE_DP * density).toInt()
        return WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            // The window size is the only size that counts: on a window's root view the
            // WindowManager overwrites the view's own LayoutParams with these, so a size set on
            // the ImageView alone would be silently ignored and the 512px source bitmap would
            // unwrap to nearly full screen.
            width = size
            height = size
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

        // The radio toggles sit in a 2 by 2 icon grid. The radios cannot be flipped by non-system
        // apps on modern Android, so every tile shows the live state and opens the matching system
        // panel with the value mirrored over.
        root.addView(
            gridRow(
                toggleCell("wifi", "W", "Wi-Fi", R.drawable.wifi) { openWifiSettings() },
                toggleCell("bluetooth", "B", "Bluetooth", R.drawable.video) { openBluetoothSettings() },
            ),
        )
        root.addView(
            gridRow(
                toggleCell("airplane", "A", "Airplane mode", R.drawable.plane) { openAirplaneSettings() },
                toggleCell("dnd", "D", "Do not disturb", R.drawable.do_not_disturb) { toggleDnd() },
            ),
        )

        // Both sliders share their apply logic between a real touch drag and the chunky D-pad
        // key steps, so the two input paths cannot drift apart.
        val applyVolume: (Int) -> Unit = { level ->
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
            } catch (_: Exception) {
                // A stream that refuses the volume (e.g. actually muted by DND) simply
                // keeps its old value.
            }
        }
        val volumeBar = SeekBar(this).apply {
            max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            progress = try {
                audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            } catch (_: Exception) {
                0
            }
            tintTrack()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) applyVolume(value)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        root.addView(
            sliderRow(
                "V",
                "Volume",
                R.drawable.volume_up,
                volumeBar,
                seekKeyHandler(volumeBar, VOLUME_SEEK_DPAD_STEP, applyVolume),
            ),
        )

        // The brightness apply logic lives on this holder and is filled in with the bar as its
        // receiver right below, so the change listener and the D-pad key handler share the exact
        // same permission / write behaviour.
        var applyBrightness: ((Int) -> Unit)? = null
        val brightnessBar = SeekBar(this).apply {
            max = BRIGHTNESS_SEEK_MAX
            progress = readBrightnessLevel(BRIGHTNESS_SEEK_MAX)
            tintTrack()
            var promptedWriteSetting = false
            applyBrightness = { value ->
                if (!Settings.System.canWrite(this@ActionBubbleService)) {
                    // Snap back to the real system value and ask for the permission once; the
                    // slider becomes live right after the user grants it.
                    progress = readBrightnessLevel(BRIGHTNESS_SEEK_MAX)
                    if (!promptedWriteSetting) {
                        promptedWriteSetting = true
                        openSettingsScreen(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    }
                } else {
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
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) applyBrightness?.invoke(value)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        root.addView(
            sliderRow(
                "B",
                "Brightness",
                R.drawable.brightness,
                brightnessBar,
                seekKeyHandler(brightnessBar, BRIGHTNESS_SEEK_DPAD_STEP) { value ->
                    applyBrightness?.invoke(value)
                },
            ),
        )
        root.addView(spacer(4f))

        // The bottom navigation row: BACK and HOME side by side. BACK gets the initial focus.
        val backButton = actionButton("\u2190  BACK") { collapsePanel() }
        val homeButton = actionButton("HOME") { openLauncher(MainActivity.TARGET_HOME) }
        root.addView(
            gridRow(backButton, homeButton).apply {
                // The navigation buttons keep the tile height so their tap targets stay stable.
                (backButton.layoutParams as LinearLayout.LayoutParams).height =
                    (ROW_HEIGHT_DP * density).toInt()
                (homeButton.layoutParams as LinearLayout.LayoutParams).height =
                    (ROW_HEIGHT_DP * density).toInt()
            },
        )

        refreshToggleStates()

        // Measure the content at the panel's real width and wrap it in a scroll area with an
        // explicit height so the panel always fits between the screen edges - never a clipped row.
        val widthPx = (PANEL_WIDTH_DP * density).toInt()
        val contentW = widthPx - 2 * (8 * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(contentW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val vPad = (8 * density).toInt()
        val screenH = resources.displayMetrics.heightPixels
        val screenMargin = (8 * density).toInt()
        val availableH = maxOf(
            (ROW_HEIGHT_DP * density).toInt(),
            screenH - 2 * screenMargin - 2 * vPad,
        )
        val scrollH = minOf(root.measuredHeight, availableH)
            .coerceAtLeast((ROW_HEIGHT_DP * density).toInt())
        val panelH = 2 * vPad + scrollH

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
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                scrollH,
            ),
        )

        panelParams = panelAnchorParams(panelH)
        windowManager.addView(panel, panelParams)
        panelWindow = panel
        // BACK is the default landing point of the panel: one push of the remote closes it.
        backButton.requestFocus()
    }

    private fun collapsePanel() {
        panelWindow?.let { windowManager.removeView(it) }
        panelWindow = null
        panelParams = null
        statusRegisters.clear()
        viewRegisters.clear()
        iconRegisters.clear()
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

    /**
     * Places two views side by side with equal weight; the icon grid rows and the BACK|HOME
     * navigation row are built with it.
     */
    private fun gridRow(first: View, second: View): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (6 * density).toInt() }
        }.apply {
            addView(
                first,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = (6 * density).toInt() },
            )
            addView(
                second,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }

    /**
     * One toggle tile of the icon grid: an icon placeholder and the ON/OFF status sit in the
     * tile's header row, while the live state shading paints the whole tile [styleToggleRow].
     */
    private fun toggleCell(
        key: String,
        initial: String,
        description: String,
        iconRes: Int? = null,
        onClick: () -> Unit,
    ): View {
        val density = resources.displayMetrics.density
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (10 * density).toInt(),
                (8 * density).toInt(),
                (10 * density).toInt(),
                (8 * density).toInt(),
            )
            isClickable = true
            contentDescription = description
        }

        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = iconPlaceholder(initial, description, iconRes)
        val status = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(TEXT_SECONDARY)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        iconRow.addView(icon)
        iconRow.addView(status)
        cell.addView(iconRow)

        // The cells have to know their key so the shared styling can shade the ones whose
        // feature is currently on.
        viewRegisters[key] = cell
        statusRegisters[key] = status
        iconRegisters[key] = icon

        cell.setOnClickListener {
            onClick()
            refreshToggleStates()
        }
        cell.setOnKeyListener(backCatcher)
        cell.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            styleToggleRow(key, hasFocus)
        }
        styleToggleRow(key, cell.isFocused)
        return cell
    }

    /**
     * The icon of a tile: a real vector drawable once it was provided, or a placeholder— a small
     * rounded square with the feature initial—until then.
     */
    private fun iconPlaceholder(initial: String, description: String, iconRes: Int? = null): View {
        val density = resources.displayMetrics.density
        val size = LinearLayout.LayoutParams(
            (24 * density).toInt(),
            (24 * density).toInt(),
        )
        if (iconRes != null) {
            return ImageView(this).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(TEXT_PRIMARY)
                contentDescription = description
                layoutParams = size
            }
        }
        return TextView(this).apply {
            text = initial
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(TEXT_PRIMARY)
            background = rounded(intArrayOf(ICON_BG, ICON_BG), radiusDp = 8f, strokeColor = STROKE)
            contentDescription = description
            layoutParams = size
        }
    }

    /**
     * Shared look of one toggle tile, combining three layers:
     *
     *  - the feature is ON: the whole tile carries a shaded accent wash, an accent stroke and an
     *    accent-colored status text, so the state is readable from across the room;
     *  - the tile has the D-pad focus: the full accent highlight on top (like the launcher tiles);
     *  - otherwise: the neutral surface look.
     */
    private fun styleToggleRow(key: String, focused: Boolean) {
        val cell = viewRegisters[key] ?: return
        val icon = iconRegisters[key] ?: return
        val status = statusRegisters[key] ?: return

        val on = status.text == "ON"
        val background = when {
            focused -> accentColor
            on -> tintOnColor
            else -> SURFACE
        }
        val stroke = if (focused || on) accentColor else STROKE
        cell.background = rounded(intArrayOf(background, background), radiusDp = 12f, strokeColor = stroke)

        if (focused) {
            // On the full accent focus highlight everything flips to the dark "on accent" ink.
            paintIcon(icon, ON_ACCENT)
            status.setTextColor(ON_ACCENT)
        } else {
            paintIcon(icon, if (on) accentColor else TEXT_PRIMARY)
            status.setTextColor(if (on) accentColor else TEXT_SECONDARY)
        }
    }

    /** Paints the icon in the shared color, whether it is a placeholder text or a vector image. */
    private fun paintIcon(icon: View, color: Int) {
        when (icon) {
            is TextView -> icon.setTextColor(color)
            is ImageView -> icon.imageTintList = ColorStateList.valueOf(color)
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
        applyRowStyle(button, focusedBg = accentColor)
        button.setOnClickListener { onClick() }
        button.setOnKeyListener(backCatcher)
        return button
    }

    /**
     * A slider row: the icon on the left of the live-seeking bar. The whole row is the D-pad
     * focus target (with the same selected look as the tiles) while the SeekBar itself is not
     * focusable: left / right changes the value through the row's key handler, so the highlight
     * wraps the full "volume" / "brightness" control while it is being selected. Touch drags on
     * the bar keep working without focus.
     */
    private fun sliderRow(
        initial: String,
        description: String,
        iconRes: Int? = null,
        bar: SeekBar,
        keyHandler: View.OnKeyListener? = null,
    ): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (ROW_HEIGHT_DP * density).toInt(),
            ).apply { bottomMargin = (6 * density).toInt() }
        }

        val icon = iconPlaceholder(initial, description, iconRes)
        row.addView(icon)
        bar.isFocusable = false
        bar.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply { leftMargin = (10 * density).toInt() }
        row.addView(bar)

        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            styleSliderRow(row, icon, hasFocus)
        }
        if (keyHandler != null) row.setOnKeyListener(keyHandler)
        styleSliderRow(row, icon, row.isFocused)
        return row
    }

    /**
     * The selected look of a slider row: instead of the tiles' full accent flood (which would
     * hide the accent-filled track), the row gets the accent wash with an accent stroke, so it
     * reads as picked while the slider stays visible.
     */
    private fun styleSliderRow(row: View, icon: View, focused: Boolean) {
        val background = if (focused) tintOnColor else SURFACE
        row.background = rounded(
            intArrayOf(background, background),
            radiusDp = 12f,
            strokeColor = if (focused) accentColor else STROKE,
        )
        paintIcon(icon, if (focused) accentColor else TEXT_PRIMARY)
    }

    /**
     * D-pad handling for one slider: BACK / B collapses the panel, left / right applies a chunky
     * step instead of the SeekBar's single-unit nudge (a 100-step bar would otherwise take a
     * hundred remote presses to cross). Returning false at the bar's edges lets the focus keep
     * travelling up / out.
     */
    private fun seekKeyHandler(bar: SeekBar, step: Int, apply: (Int) -> Unit): View.OnKeyListener =
        View.OnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else {
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> {
                        collapsePanel()
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                        val target = (bar.progress + direction * step).coerceIn(0, bar.max)
                        if (target == bar.progress) {
                            false
                        } else {
                            bar.progress = target
                            apply(target)
                            true
                        }
                    }

                    else -> false
                }
            }
        }

    /** Shared focus styling: every row highlights like the launcher tiles when it gets the focus. */
    private fun applyRowStyle(view: View, focusedBg: Int = accentColor) {
        view.isFocusable = true
        fun update(focused: Boolean) {
            val bg = if (focused) focusedBg else SURFACE
            view.background = rounded(
                intArrayOf(bg, bg),
                radiusDp = 12f,
                strokeColor = if (focused) accentColor else STROKE,
            )
        }
        view.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> update(hasFocus) }
        update(false)
    }

    private fun SeekBar.tintTrack() {
        thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
        progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
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
        const val BUBBLE_SIZE_DP = 34f
        const val EDGE_MARGIN_DP = 16f
        const val PANEL_WIDTH_DP = 248f
        const val ROW_HEIGHT_DP = 44f

        /** Chunky D-pad steps so the sliders stay remote-friendly (a touch drag stays 1:1). */
        const val VOLUME_SEEK_DPAD_STEP = 3
        const val BRIGHTNESS_SEEK_DPAD_STEP = 10

        const val BRIGHTNESS_SEEK_MAX = 100
        const val BRIGHTNESS_MIN = 10
        const val BRIGHTNESS_MAX = 255

        // The accent colors no longer live here as constants: they come from the THEMES tab and
        // are applied as instance fields (see applyAccentColors).
        const val SURFACE = 0xFF22262E.toInt()
        const val ON_ACCENT = 0xFF06101F.toInt()
        const val ICON_BG = 0xFF2B303A.toInt()
        const val PANEL_BG = 0xE6161A20.toInt()
        const val STROKE = 0xFF343A45.toInt()
        const val TEXT_PRIMARY = 0xFFE8EBF0.toInt()
        const val TEXT_SECONDARY = 0xFF9AA3B0.toInt()
    }
}
