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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
 * The console style top bar: word mark, tab strip and the system status indicators.
 */
@Composable
fun StatusBar(
    selectedTab: LauncherTab,
    onTabSelected: (LauncherTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "CONSOLE LAUNCHER",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = ConsoleTextPrimary,
        )

        Spacer(Modifier.width(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LauncherTab.entries.forEach { tab ->
                TabChip(
                    tab = tab,
                    selected = tab == selectedTab,
                    onClick = { onTabSelected(tab) },
                )
            }
        }

        Spacer(Modifier.weight(1f))

        StatusIndicators()
    }
}

@Composable
private fun TabChip(
    tab: LauncherTab,
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
            text = tab.title,
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
            active = status.wifiConnected,
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
    val signalLevel: Int,
    val wifiConnected: Boolean,
    val wifiEnabled: Boolean,
)

@Composable
private fun rememberSystemStatus(context: Context): androidx.compose.runtime.State<SystemStatus> {
    val status = remember {
        mutableStateOf(
            SystemStatus(
                clock = context.readClock(),
                batteryLevel = context.readBatteryLevel(),
                charging = false,
                signalLevel = 0,
                wifiConnected = false,
                wifiEnabled = false,
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

    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            val wifi = context.readWifiState()
            status.value = status.value.copy(
                clock = context.readClock(),
                signalLevel = wifi.level,
                wifiConnected = wifi.connected,
                wifiEnabled = wifi.enabled,
            )
            delay(1_000L)
        }
    }
    return status
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
        val networkRssi = (capabilities.transportInfo as? WifiInfo)?.rssi ?: Int.MIN_VALUE
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

    val level = if (connected && rssi != Int.MIN_VALUE) rssiToSignalLevel(rssi) else 0
    return WifiState(connected = connected, enabled = enabled, level = level)
}

private data class WifiState(
    val connected: Boolean,
    val enabled: Boolean,
    val level: Int,
)

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
