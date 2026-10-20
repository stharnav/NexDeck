package com.example.consolelauncher.ui

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.consolelauncher.data.AppEntry
import com.example.consolelauncher.data.AppRepository
import com.example.consolelauncher.data.RecentAppsStore
import com.example.consolelauncher.ui.components.LauncherGrid
import com.example.consolelauncher.ui.components.LauncherRow
import com.example.consolelauncher.ui.components.LauncherTab
import com.example.consolelauncher.ui.components.StatusBar
import com.example.consolelauncher.ui.theme.ConsoleTextSecondary

/**
 * The launcher home screen. It shows the applications installed on the device as console style
 * tiles and can be driven with a touch screen, a D-pad or the arrow keys.
 */
@Composable
fun LauncherRoot(
    refreshKey: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember { AppRepository(context) }
    val recentStore = remember { RecentAppsStore(context) }

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var recents by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var selectedTab by remember { mutableStateOf(LauncherTab.HOME) }

    LaunchedEffect(refreshKey) {
        val loaded = repository.loadLaunchableApps()
        apps = loaded
        recents = recentsFrom(recentStore, loaded)
    }

    fun launch(entry: AppEntry) {
        recentStore.record(entry.packageName)
        recents = recentsFrom(recentStore, apps)
        val intent = context.packageManager.getLaunchIntentForPackage(entry.packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF171B22), Color(0xFF0B0D11)),
                )
            )
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_L1 -> {
                        selectedTab = selectedTab.previous()
                        true
                    }

                    KeyEvent.KEYCODE_BUTTON_R1 -> {
                        selectedTab = selectedTab.next()
                        true
                    }

                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> {
                        if (selectedTab != LauncherTab.HOME) selectedTab = LauncherTab.HOME
                        true
                    }

                    else -> false
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            StatusBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
            )

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    LauncherTab.HOME -> LauncherGrid(
                        rows = homeRows(recents, apps),
                        onLaunch = ::launch,
                        autoFocusFirstItem = true,
                    )

                    LauncherTab.RECENT -> LauncherGrid(
                        rows = recentRows(recents),
                        onLaunch = ::launch,
                    )

                    LauncherTab.ALL_APPS -> LauncherGrid(
                        rows = allAppsRows(apps),
                        onLaunch = ::launch,
                    )
                }
            }

            HintBar()
        }
    }
}

@Composable
private fun HintBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Hint("D-PAD", "NAVIGATE")
        Hint("ENTER", "LAUNCH")
        Hint("BACK", "HOME")
        Hint("L1 / R1", "SWITCH TAB")
    }
}

@Composable
private fun Hint(key: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = action,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            color = ConsoleTextSecondary,
        )
    }
}

private fun homeRows(recents: List<AppEntry>, apps: List<AppEntry>): List<LauncherRow> = buildList {
    add(LauncherRow.Section("CONTINUE"))
    if (recents.isEmpty()) {
        add(LauncherRow.Message("Nothing here yet - launch an app to build your history"))
    } else {
        recents.take(4).forEach { add(LauncherRow.App("continue_${it.packageName}", it)) }
    }
    add(LauncherRow.Section("ALL APPS"))
    val alreadyShown = recents.map { it.packageName }.toSet()
    val remaining = apps.filterNot { it.packageName in alreadyShown }
    if (remaining.isEmpty()) {
        add(LauncherRow.Message("Loading installed applications..."))
    } else {
        remaining.take(8).forEach { add(LauncherRow.App("all_${it.packageName}", it)) }
    }
}

private fun recentRows(recents: List<AppEntry>): List<LauncherRow> = buildList {
    add(LauncherRow.Section("RECENTLY LAUNCHED"))
    if (recents.isEmpty()) {
        add(LauncherRow.Message("No recent apps yet - launch something from the home screen"))
    } else {
        recents.forEach { add(LauncherRow.App("recent_${it.packageName}", it)) }
    }
}

private fun allAppsRows(apps: List<AppEntry>): List<LauncherRow> = buildList {
    if (apps.isEmpty()) {
        add(LauncherRow.Section("ALL APPS"))
        add(LauncherRow.Message("Loading installed applications..."))
        return@buildList
    }
    apps.groupBy { it.label.firstOrNull()?.uppercaseChar()?.toString() ?: "#" }
        .toSortedMap()
        .forEach { (letter, entries) ->
            add(LauncherRow.Letter(letter))
            entries.forEach { add(LauncherRow.App("all_${it.packageName}", it)) }
        }
}

private fun recentsFrom(store: RecentAppsStore, apps: List<AppEntry>): List<AppEntry> {
    val byPackage = apps.associateBy { it.packageName }
    return store.recentPackages().mapNotNull { byPackage[it] }
}

private fun LauncherTab.next(): LauncherTab {
    val values = LauncherTab.entries
    return values[(ordinal + 1) % values.size]
}

private fun LauncherTab.previous(): LauncherTab {
    val values = LauncherTab.entries
    return values[(ordinal - 1 + values.size) % values.size]
}
