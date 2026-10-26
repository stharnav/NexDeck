package com.example.consolelauncher.ui

import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.consolelauncher.data.RetroArchRepository
import com.example.consolelauncher.data.RetroGameEntry
import com.example.consolelauncher.ui.components.LauncherGrid
import com.example.consolelauncher.ui.components.LauncherRow
import com.example.consolelauncher.ui.components.LauncherTab
import com.example.consolelauncher.ui.components.StatusBar
import com.example.consolelauncher.ui.theme.ConsoleTextSecondary

/**
 * The launcher home screen. It shows the applications installed on the device as console style
 * tiles, plus the games that RetroArch knows about, and can be driven with a touch screen, a D-pad
 * or the arrow keys.
 */
@Composable
fun LauncherRoot(
    refreshKey: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember { AppRepository(context) }
    val recentStore = remember { RecentAppsStore(context) }
    val retroRepository = remember { RetroArchRepository(context) }
    val playlistAccess = remember { retroRepository.getPlaylistAccess() }

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var recents by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var selectedTab by remember { mutableStateOf(LauncherTab.HOME) }

    var retroGames by remember { mutableStateOf<List<RetroGameEntry>>(emptyList()) }
    var retroArchAvailable by remember { mutableStateOf(false) }
    var retroHasPlaylistAccess by remember { mutableStateOf(false) }
    var retroHasArtworkAccess by remember { mutableStateOf(false) }
    var retroLoading by remember { mutableStateOf(false) }
    var retroLoaded by remember { mutableStateOf(false) }
    var retroError by remember { mutableStateOf<String?>(null) }
    var retroRevision by remember { mutableIntStateOf(0) }

    val playlistPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (playlistAccess.handleResult(result.resultCode, result.data)) {
            retroError = null
            retroRevision++
        }
    }

    LaunchedEffect(refreshKey) {
        val loaded = repository.loadLaunchableApps()
        apps = loaded
        recents = recentsFrom(recentStore, loaded)
    }

    LaunchedEffect(refreshKey, retroRevision) {
        retroRepository.refresh()
        retroArchAvailable = retroRepository.isAvailable()
        retroHasPlaylistAccess = retroRepository.hasPlaylistAccess()
        retroHasArtworkAccess = retroRepository.hasThumbnailAccess()

        if (retroArchAvailable && retroHasPlaylistAccess) {
            retroLoading = true
            retroGames = retroRepository.loadRetroGames()
            retroLoading = false
        } else {
            retroGames = emptyList()
        }
        retroLoaded = true
    }

    fun launch(entry: AppEntry) {
        recentStore.record(entry.packageName)
        recents = recentsFrom(recentStore, apps)
        val intent = context.packageManager.getLaunchIntentForPackage(entry.packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun launchGame(entry: RetroGameEntry) {
        retroError = null
        retroRepository.grantRomAccess(entry.romPath, entry.launchPackage)
        try {
            context.startActivity(entry.launchIntent)
            retroRepository.packageName?.let { pkg ->
                recentStore.record(pkg)
                recents = recentsFrom(recentStore, apps)
            }
        } catch (e: Exception) {
            retroError = "Could not launch ${entry.label}. Is RetroArch still installed?"
        }
    }

    fun handleGameAction(action: LauncherRow.Action) {
        when (action.key) {
            ACTION_GRANT_PLAYLISTS, ACTION_CHANGE_PLAYLISTS -> {
                retroError = null
                playlistAccess.requestAccess(playlistPicker)
            }

            ACTION_REFRESH_GAMES -> retroRevision++

            ACTION_SHOW_ARTWORK -> {
                retroError = null
                playlistAccess.requestAccess(playlistPicker, startInParent = true)
            }
        }
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
                        onLaunchApp = ::launch,
                        autoFocusFirstItem = true,
                    )

                    LauncherTab.GAMES -> LauncherGrid(
                        rows = gameRows(
                            loaded = retroLoaded,
                            available = retroArchAvailable,
                            hasPlaylistAccess = retroHasPlaylistAccess,
                            hasArtworkAccess = retroHasArtworkAccess,
                            loading = retroLoading,
                            games = retroGames,
                            error = retroError,
                        ),
                        onLaunchApp = ::launch,
                        onLaunchGame = ::launchGame,
                        onAction = ::handleGameAction,
                        onLoadThumbnail = { paths -> retroRepository.loadThumbnail(paths) },
                        autoFocusFirstItem = true,
                    )

                    LauncherTab.RECENT -> LauncherGrid(
                        rows = recentRows(recents),
                        onLaunchApp = ::launch,
                    )

                    LauncherTab.ALL_APPS -> LauncherGrid(
                        rows = allAppsRows(apps),
                        onLaunchApp = ::launch,
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

/**
 * The GAMES tab: either the games from RetroArch's playlists, or an explanation of what is
 * missing, with a focusable action to fix it.
 */
private fun gameRows(
    loaded: Boolean,
    available: Boolean,
    hasPlaylistAccess: Boolean,
    hasArtworkAccess: Boolean,
    loading: Boolean,
    games: List<RetroGameEntry>,
    error: String?,
): List<LauncherRow> = buildList {
    add(LauncherRow.Section("GAMES"))

    when {
        !loaded || (loading && games.isEmpty()) -> {
            add(LauncherRow.Message("Reading RetroArch playlists..."))
        }

        !available -> {
            add(
                LauncherRow.Message(
                    "RetroArch is not installed. Install RetroArch to play your ROM collection from here.",
                ),
            )
        }

        !hasPlaylistAccess -> {
            add(
                LauncherRow.Message(
                    "Grant access to the folder where RetroArch keeps its playlists to list your games here.",
                ),
            )
            add(
                LauncherRow.Action(
                    key = ACTION_GRANT_PLAYLISTS,
                    label = "GRANT PLAYLIST ACCESS",
                    description = "Pick the RetroArch folder that contains the playlists folder",
                ),
            )
        }

        games.isEmpty() -> {
            add(LauncherRow.Message(error ?: "No games found in the selected folder."))
            add(
                LauncherRow.Action(
                    key = ACTION_GRANT_PLAYLISTS,
                    label = "CHOOSE ANOTHER FOLDER",
                    description = "Pick the folder that contains your RetroArch .lpl playlists",
                ),
            )
            add(
                LauncherRow.Action(
                    key = ACTION_REFRESH_GAMES,
                    label = "REFRESH",
                    description = "Scan the selected folder again",
                ),
            )
        }

        else -> {
            if (error != null) add(LauncherRow.Message(error))
            if (!hasArtworkAccess) {
                add(
                    LauncherRow.Action(
                        key = ACTION_SHOW_ARTWORK,
                        label = "SHOW GAME ARTWORK",
                        description = "Grant access to the RetroArch folder that contains the thumbnails for box art",
                    ),
                )
            }
            games.groupBy { it.playlistName }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .forEach { (playlist, entries) ->
                    add(LauncherRow.Section(playlist.uppercase(), key = "playlist_$playlist"))
                    entries.forEach { add(LauncherRow.Game(it.gridKey, it)) }
                }
            add(
                LauncherRow.Action(
                    key = ACTION_CHANGE_PLAYLISTS,
                    label = "CHANGE PLAYLIST FOLDER",
                    description = "Scan a different RetroArch playlists folder",
                ),
            )
        }
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

private const val ACTION_GRANT_PLAYLISTS = "games_grant_playlists"
private const val ACTION_CHANGE_PLAYLISTS = "games_change_playlists"
private const val ACTION_REFRESH_GAMES = "games_refresh"
private const val ACTION_SHOW_ARTWORK = "games_show_artwork"
