package com.example.consolelauncher.ui.components

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.consolelauncher.ui.theme.ConsoleAccent
import com.example.consolelauncher.ui.theme.ConsoleOnAccent
import com.example.consolelauncher.ui.theme.ConsoleSurface
import com.example.consolelauncher.ui.theme.ConsoleTextPrimary
import com.example.consolelauncher.ui.theme.ConsoleTextSecondary
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The tabs of the launcher. */
enum class LauncherTab(val title: String) {
    HOME("HOME"),
    GAMES("GAMES"),
    RECENT("RECENT"),
    ALL_APPS("ALL APPS"),
}

/**
 * The console style top bar: drawer toggle, word mark, tab strip and the system status indicators.
 *
 * The tab strip is generic: the HOME screen passes its tabs, the SETTINGS screen its own. Passing
 * no tabs at all renders the plain bar used by the other drawer menus.
 */
@Composable
fun StatusBar(
    tabTitles: List<String>,
    selectedTabTitle: String?,
    onTabSelected: (String) -> Unit,
    onOpenDrawer: () -> Unit = {},
    modifier: Modifier = Modifier,
    wordmark: String = "CONSOLE LAUNCHER",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HamburgerButton(onClick = onOpenDrawer)

        Spacer(Modifier.width(12.dp))

        Text(
            text = wordmark,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = ConsoleTextPrimary,
        )

        if (tabTitles.isNotEmpty()) {
            Spacer(Modifier.width(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabTitles.forEach { title ->
                    TabChip(
                        title = title,
                        selected = title == selectedTabTitle,
                        onClick = { onTabSelected(title) },
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        StatusIndicators()
    }
}

/** The drawer toggle: a hamburger glyph styled like the tab chips, so it also lights up on focus. */
@Composable
private fun HamburgerButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) ConsoleAccent else ConsoleSurface)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(width = 18.dp, height = 14.dp)) {
            val color = if (focused) ConsoleOnAccent else ConsoleTextPrimary
            val centerY = size.height / 2f
            for (offset in listOf(-4.dp, 0.dp, 4.dp)) {
                val y = centerY + offset.toPx()
                drawLine(
                    color = color,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun TabChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ConsoleAccent else ConsoleSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 1.sp,
            color = if (selected) ConsoleOnAccent else ConsoleTextSecondary,
        )
    }
}

@Composable
private fun StatusIndicators() {
    val context = LocalContext.current
    val status by rememberSystemStatus(context)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WifiGlyph(
            connected = status.wifiConnected,
            enabled = status.wifiEnabled,
        )
        SignalBars(
            level = status.signalLevel,
            active = status.wifiConnected || status.cellConnected,
        )
        Spacer(Modifier.width(6.dp))
        BatteryGlyph(
            level = status.batteryLevel,
            charging = status.charging,
        )
        Text(
            text = "${status.batteryLevel}%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = ConsoleTextPrimary,
        )
        if (status.charging) {
            ChargingBolt()
        }
        Text(
            text = status.clock,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = ConsoleTextPrimary,
        )
    }
}

/**
 * The Wi-Fi icon: three arcs over a dot. It is always drawn, so the user can tell the difference
 * between "radio off", "radio on but not connected" and "connected".
 */
@Composable
private fun WifiGlyph(connected: Boolean, enabled: Boolean) {
    Canvas(modifier = Modifier.size(width = 18.dp, height = 16.dp)) {
        val color = when {
            connected -> ConsoleTextPrimary
            enabled -> ConsoleTextSecondary
            else -> ConsoleTextSecondary.copy(alpha = 0.45f)
        }
        val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round)
        val base = Offset(size.width / 2f, size.height - 2.5.dp.toPx())
        drawCircle(color, radius = 1.6.dp.toPx(), center = base)
        listOf(4.5.dp, 8.dp, 11.5.dp).forEach { radius ->
            val r = radius.toPx()
            drawArc(
                color = color,
                startAngle = 215f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(base.x - r, base.y - r),
                size = Size(r * 2f, r * 2f),
                style = stroke,
            )
        }
    }
}

/** Four ascending bars, filled up to the current signal level. */
@Composable
private fun SignalBars(level: Int, active: Boolean) {
    Canvas(modifier = Modifier.size(width = 16.dp, height = 12.dp)) {
        val gap = 1.5.dp.toPx()
        val barWidth = (size.width - gap * 3f) / 4f
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        for (index in 0 until 4) {
            val fraction = (index + 1) / 4f
            val barHeight = size.height * fraction
            val left = index * (barWidth + gap)
            val filled = active && index < level
            drawRoundRect(
                color = if (filled) ConsoleTextPrimary else ConsoleTextSecondary.copy(alpha = 0.5f),
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
private fun BatteryGlyph(level: Int, charging: Boolean) {
    val accent = when {
        level <= 15 -> Color(0xFFFF6B6B)
        level <= 30 -> Color(0xFFFFC44D)
        charging -> Color(0xFF5BD675)
        else -> ConsoleAccent
    }
    Canvas(modifier = Modifier.size(width = 24.dp, height = 13.dp)) {
        val strokeWidth = 1.5.dp.toPx()
        val bodyWidth = size.width - 3.dp.toPx()
        val bodyHeight = size.height
        drawRoundRect(
            color = ConsoleTextPrimary,
            topLeft = Offset.Zero,
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(width = strokeWidth),
        )
        drawRoundRect(
            color = ConsoleTextPrimary,
            topLeft = Offset(bodyWidth + 1.dp.toPx(), bodyHeight / 3f),
            size = Size(2.dp.toPx(), bodyHeight / 3f),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        val inset = 2.5.dp.toPx()
        val fillWidth = (bodyWidth - inset * 2f) * (level.coerceIn(0, 100) / 100f)
        drawRoundRect(
            color = accent,
            topLeft = Offset(inset, inset),
            size = Size(fillWidth, bodyHeight - inset * 2f),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
    }
}

/** A small lightning bolt shown while the device is charging. */
@Composable
private fun ChargingBolt() {
    Canvas(modifier = Modifier.size(width = 9.dp, height = 14.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.62f, 0f)
            lineTo(size.width * 0.12f, size.height * 0.55f)
            lineTo(size.width * 0.46f, size.height * 0.55f)
            lineTo(size.width * 0.34f, size.height)
            lineTo(size.width * 0.88f, size.height * 0.42f)
            lineTo(size.width * 0.52f, size.height * 0.42f)
            close()
        }
        drawPath(path, color = Color(0xFF5BD675))
    }
}

/** Everything the top bar shows about the state of the device. */
private data class SystemStatus(
    val clock: String,
    val batteryLevel: Int,
    val charging: Boolean,
    val wifiLevel: Int,
    val wifiConnected: Boolean,
    val wifiEnabled: Boolean,
    val cellConnected: Boolean,
    val cellularLevel: Int,
) {
    /** The bars show whichever network is actually carrying the traffic. */
    val signalLevel: Int
        get() = when {
            wifiConnected -> wifiLevel
            cellConnected -> cellularLevel
            else -> 0
        }
}

// The pre-R phone state listener classes are deprecated but are the only permission-free
// push source for cellular strength on API 26-29; the suppression is scoped to this function.
@Suppress("DEPRECATION")
@Composable
private fun rememberSystemStatus(context: Context): androidx.compose.runtime.State<SystemStatus> {
    val status = remember {
        mutableStateOf(
            SystemStatus(
                clock = context.readClock(),
                batteryLevel = context.readBatteryLevel(),
                charging = false,
                wifiLevel = 0,
                wifiConnected = false,
                wifiEnabled = false,
                cellConnected = false,
                cellularLevel = 0,
            )
        )
    }

    // The battery broadcast is the only reliable source for the level and the charging state.
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                val extraLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val extraStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                status.value = status.value.copy(
                    batteryLevel = if (extraLevel >= 0) extraLevel else status.value.batteryLevel,
                    charging = extraStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                        extraStatus == BatteryManager.BATTERY_STATUS_FULL,
                )
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Below R the cached signal strength is not readable, so the phone state listener pushes
    // the cellular level. It is the same mechanism the platform status bar used pre-R; signal
    // strength listening never required a permission.
    DisposableEffect(context) {
        val telephony = context.telephonyManager()
        val listener: PhoneStateListener? =
            if (telephony != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                object : PhoneStateListener() {
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                        if (signalStrength == null) return
                        status.value = status.value.copy(cellularLevel = signalStrength.level)
                    }
                }.also {
                    try {
                        telephony.listen(it, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
                    } catch (_: RuntimeException) {
                        // Some builds refuse the registration; the bars will just stay dark.
                    }
                }
            } else {
                null
            }
        onDispose {
            if (listener != null) telephony?.listen(listener, PhoneStateListener.LISTEN_NONE)
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            val wifi = context.readWifiState()
            status.value = status.value.copy(
                clock = context.readClock(),
                wifiLevel = wifi.level,
                wifiConnected = wifi.connected,
                wifiEnabled = wifi.enabled,
            )
            context.readCellularStatus(status)

            delay(1_000L)
        }
    }
    return status
}

/**
 * Refreshes the cellular fields of [status]. On R and above the cached modem reading answers
 * synchronously; on older versions the phone state listener owns the level, so only the
 * service state is refreshed here.
 */
@Suppress("DEPRECATION")
private fun Context.readCellularStatus(status: androidx.compose.runtime.MutableState<SystemStatus>) {
    val telephony = telephonyManager()
    val cellConnected = hasCellularService()
    val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            telephony?.signalStrength?.level ?: 0
        } catch (_: RuntimeException) {
            // Radio-less TVs throw UnsupportedOperationException, and some builds keep the
            // reading permission-gated; both cases leave the level untouched.
            null
        }
    } else {
        null
    }
    status.value = if (level != null) {
        status.value.copy(cellConnected = cellConnected, cellularLevel = level)
    } else {
        status.value.copy(cellConnected = cellConnected)
    }
}

private fun Context.readClock(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private fun Context.readBatteryLevel(): Int {
    val manager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    return manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
}

/**
 * Reads the state of the Wi-Fi connection.
 *
 * All networks are inspected instead of only the active one: when a VPN or the cellular network
 * is the default route, the Wi-Fi connection is still there and the icon has to show it.
 *
 * The RSSI comes from the capability snapshot's signal strength field; the `WifiInfo` embedded
 * in `transportInfo` is not reliably delivered to third-party apps on every build.
 */
@Suppress("DEPRECATION")
private fun Context.readWifiState(): WifiState {
    val manager = connectivityManager()
    val wifi = wifiManager()
    val enabled = wifi?.isWifiEnabled == true

    var connected = false
    var rssi = Int.MIN_VALUE
    manager?.allNetworks?.forEach { network ->
        val capabilities = manager.getNetworkCapabilities(network) ?: return@forEach
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@forEach
        connected = true
        // Some builds strip the WifiInfo out of transportInfo for third-party apps; the
        // bearer-specific signal strength field (RSSI for Wi-Fi) survives that redaction.
        val fromTransportInfo = (capabilities.transportInfo as? WifiInfo)?.rssi ?: Int.MIN_VALUE
        val fromSnapshot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            capabilities.signalStrength
        } else {
            Int.MIN_VALUE
        }
        val networkRssi = when {
            isValidRssi(fromTransportInfo) -> fromTransportInfo
            isValidRssi(fromSnapshot) -> fromSnapshot
            else -> fromTransportInfo
        }
        if (networkRssi > rssi) rssi = networkRssi
    }

    if (!connected && enabled) {
        // Fall back to the Wi-Fi manager for the case that the Wi-Fi network is not the default.
        val info = wifi?.connectionInfo
        val fallbackRssi = info?.rssi ?: Int.MIN_VALUE
        if (info?.supplicantState == SupplicantState.COMPLETED && fallbackRssi > -100) {
            connected = true
            rssi = fallbackRssi
        }
    }

    val level = if (connected && isValidRssi(rssi)) rssiToSignalLevel(rssi) else 0
    return WifiState(connected = connected, enabled = enabled, level = level)
}

private data class WifiState(
    val connected: Boolean,
    val enabled: Boolean,
    val level: Int,
)

/** An RSSI this low is either the unconnected placeholder (or a redacted reading) and carries no level. */
private fun isValidRssi(rssi: Int): Boolean = rssi > -100

/** The same buckets the platform uses for the signal strength. */
private fun rssiToSignalLevel(rssi: Int): Int = when {
    rssi >= -55 -> 4
    rssi >= -66 -> 3
    rssi >= -77 -> 2
    rssi >= -88 -> 1
    else -> 0
}

private fun Context.activeNetworkCapabilities(): NetworkCapabilities? {
    val manager = connectivityManager() ?: return null
    val network = manager.activeNetwork ?: return null
    return manager.getNetworkCapabilities(network)
}

private fun Context.activeWifiInfo(): WifiInfo? =
    activeNetworkCapabilities()?.transportInfo as? WifiInfo

private fun Context.connectivityManager(): ConnectivityManager? =
    getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
private fun Context.wifiManager(): WifiManager? =
    getSystemService(Context.WIFI_SERVICE) as? WifiManager
private fun Context.telephonyManager(): TelephonyManager? =
    getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

/**
 * Whether a cellular radio is registered with a carrier: either the radio reports service or
 * there is a live cellular network in the connectivity manager. Devices without a radio fail
 * both checks, so the bars never claim a cellular signal.
 */
@Suppress("DEPRECATION")
private fun Context.hasCellularService(): Boolean {
    val telephony = telephonyManager() ?: return false
    // Newer Android builds gate the service state behind READ_PHONE_STATE; without the
    // permission the binder call throws instead of returning an empty state.
    val serviceState = try {
        telephony.serviceState
    } catch (_: SecurityException) {
        null
    }
    if (serviceState?.state == ServiceState.STATE_IN_SERVICE) return true
    val manager = connectivityManager() ?: return false
    return manager.allNetworks.any { network ->
        manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }
}
