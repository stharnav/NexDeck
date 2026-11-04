package com.example.consolelauncher.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.consolelauncher.MainActivity
import com.example.consolelauncher.bubble.ActionBubbleService
import com.example.consolelauncher.data.AppEntry
import com.example.consolelauncher.data.AppRepository
import com.example.consolelauncher.data.GalleryImage
import com.example.consolelauncher.data.LauncherSettingsStore
import com.example.consolelauncher.data.RecentAppsStore
import com.example.consolelauncher.data.RetroArchRepository
import com.example.consolelauncher.data.RetroGameEntry
import com.example.consolelauncher.data.UserProfileStore
import com.example.consolelauncher.ui.components.LauncherGameSystems
import com.example.consolelauncher.ui.components.LauncherSettings
import com.example.consolelauncher.ui.components.ScreenshotsScreen
import com.example.consolelauncher.ui.components.ScreenshotsTab
import com.example.consolelauncher.ui.components.SettingsTab
import com.example.consolelauncher.ui.components.ACTION_CHANGE_PLAYLISTS
import com.example.consolelauncher.ui.components.ACTION_GRANT_PLAYLISTS
import com.example.consolelauncher.ui.components.ACTION_REFRESH_GAMES
import com.example.consolelauncher.ui.components.DrawerMenuItem
import com.example.consolelauncher.ui.components.DrawerOverlay
import com.example.consolelauncher.ui.components.EmptyScreen
import com.example.consolelauncher.ui.components.LauncherGrid
import com.example.consolelauncher.ui.components.LauncherRow
import com.example.consolelauncher.ui.components.LauncherTab
import com.example.consolelauncher.ui.components.StatusBar
import com.example.consolelauncher.ui.components.launchSteamLink
import com.example.consolelauncher.ui.components.cycleSettingsTab
import com.example.consolelauncher.ui.theme.AccentOption
import com.example.consolelauncher.ui.theme.ConsoleBackground
import com.example.consolelauncher.ui.theme.ConsoleTextSecondary
import com.example.consolelauncher.ui.theme.accentOptionById
import com.example.consolelauncher.ui.theme.setAccentOption

/**
 * The launcher home screen. It shows recently launched apps plus the RetroArch play history and
 * favourites as console style tiles, and all games that RetroArch knows about, and can be driven
 * with a touch screen, a D-pad or the arrow keys.
 */
@Composable
fun LauncherRoot(
    refreshKey: Int,
    pendingTarget: String? = null,
    onTargetConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember { AppRepository(context) }
    val recentStore = remember { RecentAppsStore(context) }
    val retroRepository = remember { RetroArchRepository(context) }
    val profileStore = remember { UserProfileStore(context) }
    val settingsStore = remember { LauncherSettingsStore(context) }
    val playlistAccess = remember { retroRepository.getPlaylistAccess() }

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var recents by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var selectedTab by remember { mutableStateOf(LauncherTab.HOME) }
    var selectedSettingsTab by remember { mutableStateOf(SettingsTab.USER) }
    var selectedScreenshotsTab by remember { mutableStateOf(ScreenshotsTab.IMAGES) }
    /** Index within the open screenshots tab' images of the fullscreen viewer, -1 = closed. */
    var screenshotViewerIndex by remember { mutableIntStateOf(-1) }
    var savedName by remember { mutableStateOf(profileStore.name()) }
    var ambientMode by remember { mutableStateOf(settingsStore.ambientMode()) }
    var actionBubble by remember { mutableStateOf(settingsStore.actionBubble()) }
    var accentOption by remember {
        mutableStateOf(accentOptionById(settingsStore.accentColor()))
    }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    /** Last game the D-pad (or a tap) landed on, for the ambient mode background. */
    var hoveredGame by remember { mutableStateOf<RetroGameEntry?>(null) }
    var selectedMenu by remember { mutableStateOf(DrawerMenuItem.HOME) }
    var drawerOpen by remember { mutableStateOf(false) }
    // Bumped every time the drawer closes so the screen content grabs the D-pad focus back.
    var focusEpoch by remember { mutableIntStateOf(0) }
    var steamLinkMissing by remember { mutableStateOf(false) }

    var retroGames by remember { mutableStateOf<List<RetroGameEntry>>(emptyList()) }
    var historyGames by remember { mutableStateOf<List<RetroGameEntry>>(emptyList()) }
    var favouriteGames by remember { mutableStateOf<List<RetroGameEntry>>(emptyList()) }
    var galleryImages by remember { mutableStateOf<List<GalleryImage>>(emptyList()) }
    var savedScreenshots by remember { mutableStateOf<List<GalleryImage>>(emptyList()) }
    var galleryLoading by remember { mutableStateOf(false) }
    /** True once the gallery scan ran at least once, so "no images" is not shown before it. */
    var galleryLoaded by remember { mutableStateOf(false) }
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

    // The overlay permission lives on its own system settings page (no runtime dialog). Flipping
    // the ACTION BUBBLE switch on without it opens that page; the switch only turns on for real
    // once the user comes back with the permission granted.
    val overlayPermissionRequest = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        overlayGranted = Settings.canDrawOverlays(context)
        if (overlayGranted) {
            settingsStore.setActionBubble(true)
            actionBubble = true
        }
    }

    LaunchedEffect(refreshKey) {
        val loaded = repository.loadLaunchableApps()
        apps = loaded
        recents = recentsFrom(recentStore, loaded)
    }

    // The ACTION BUBBLE can be granted (or revoked) from the system settings while the launcher
    // is away, and its HIDE shortcut re-writes the switch itself; re-sync both on every return.
    LaunchedEffect(refreshKey) {
        overlayGranted = Settings.canDrawOverlays(context)
        actionBubble = settingsStore.actionBubble()
    }

    // Keep the bubble overlay service aligned with the switch and the permission.
    LaunchedEffect(actionBubble, overlayGranted) {
        val service = Intent(context, ActionBubbleService::class.java)
        try {
            if (actionBubble && overlayGranted) context.startService(service)
            else context.stopService(service)
        } catch (_: Exception) {
            // Nothing on screen depends on the bubble; a failed start simply leaves it hidden.
        }
    }

    // A bubble shortcut intent can ask the launcher to open straight on the HOME grid or the
    // ALL APPS tab; consume the request once the state was applied.
    LaunchedEffect(pendingTarget) {
        when (pendingTarget) {
            MainActivity.TARGET_HOME -> {
                drawerOpen = false
                selectedMenu = DrawerMenuItem.HOME
                selectedTab = LauncherTab.HOME
            }

            MainActivity.TARGET_APPS -> {
                drawerOpen = false
                selectedMenu = DrawerMenuItem.HOME
                selectedTab = LauncherTab.ALL_APPS
            }
        }
        if (pendingTarget != null) onTargetConsumed()
    }

    LaunchedEffect(refreshKey, retroRevision, ambientMode) {
        retroRepository.refresh()
        retroArchAvailable = retroRepository.isAvailable()
        retroHasPlaylistAccess = retroRepository.hasPlaylistAccess()
        retroHasArtworkAccess = retroRepository.hasThumbnailAccess()

        if (retroArchAvailable && retroHasPlaylistAccess) {
            retroLoading = true
            // Snaps (ambient backgrounds) are only resolved while ambient mode is active, since
            // scanning a second artwork folder per playlist makes the load noticeably slower.
            retroGames = retroRepository.loadRetroGames(resolveSnaps = ambientMode)
            historyGames = retroRepository.loadHistory(resolveSnaps = ambientMode)
            favouriteGames = retroRepository.loadFavourites(resolveSnaps = ambientMode)
            retroLoading = false
        } else {
            retroGames = emptyList()
            historyGames = emptyList()
            favouriteGames = emptyList()
        }
        retroLoaded = true
    }

    // The SCREENSHOTS gallery scans lazily: the playlists and their thumbnails folders are only
    // walked the first time the menu is opened (and after a scan had run for the games list, so
    // the access flags are up to date). Images stay in memory, so re-entering is instant.
    LaunchedEffect(selectedMenu, retroRevision, retroArchAvailable, retroHasPlaylistAccess) {
        if (selectedMenu != DrawerMenuItem.SCREENSHOTS) return@LaunchedEffect
        if (!retroArchAvailable || !retroHasPlaylistAccess) {
            galleryLoaded = true
            return@LaunchedEffect
        }
        galleryLoading = true
        galleryImages = retroRepository.loadPlaylistImages()
        savedScreenshots = retroRepository.loadSavedScreenshots()
        galleryLoading = false
        galleryLoaded = true
    }

    // A different tab or screen shows different games; forget the hovered one so its snap does
    // not linger as the ambient background.
    LaunchedEffect(selectedMenu, selectedTab) {
        hoveredGame = null
    }

    // The ambient background: the Named_Snaps screenshot of the focused game, only while
    // ambient mode is on. produceState cancels a stale load when the hover or the switch
    // changes, so a slow decode can no longer overwrite a newer background.
    val ambientSnap by produceState<ImageBitmap?>(
        initialValue = null,
        hoveredGame,
        ambientMode,
    ) {
        value = if (!ambientMode) {
            null
        } else {
            hoveredGame?.snapUri?.let { retroRepository.loadSnap(it) }
        }
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

    fun saveName(name: String) {
        profileStore.save(name)
        // Commit is synchronous, so the trimmed name already round-trips through the store.
        savedName = profileStore.name()
    }

    fun toggleAmbientMode(enabled: Boolean) {
        settingsStore.setAmbientMode(enabled)
        // Commit is synchronous, so the flag already round-trips through the store.
        ambientMode = enabled
    }

    fun cycleAccentOption() {
        val values = AccentOption.entries
        val next = values[(accentOption.ordinal + 1) % values.size]
        settingsStore.setAccentColor(next.id)
        setAccentOption(next)
        accentOption = next
        // The ACTION BUBBLE only beds in its colors when the service starts, so a running
        // bubble restarts to pick up the new accent (the panel re-styles on every open).
        if (actionBubble && overlayGranted) {
            val service = Intent(context, ActionBubbleService::class.java)
            try {
                context.stopService(service)
                context.startService(service)
            } catch (_: Exception) {
                // Losing just the bubble is fine; the launcher keeps its own new colors.
            }
        }
    }

    fun toggleActionBubble(enabled: Boolean) {
        if (enabled && !overlayGranted) {
            try {
                overlayPermissionRequest.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            } catch (_: Exception) {
                // Some TV builds ship no such settings page; re-check in case it is already there.
                overlayGranted = Settings.canDrawOverlays(context)
            }
            return
        }
        settingsStore.setActionBubble(enabled)
        // Commit is synchronous, so the flag already round-trips through the store.
        actionBubble = enabled
    }

    fun handleMenu(item: DrawerMenuItem) {
        steamLinkMissing = false
        drawerOpen = false
        focusEpoch++
        selectedMenu = item
        // Navigating away from the SCREENSHOTS menu also closes its fullscreen viewer.
        screenshotViewerIndex = -1
        if (item == DrawerMenuItem.STEAM && !context.launchSteamLink()) {
            // No Steam Link installed: the STEAM screen explains it instead of going blank.
            steamLinkMissing = true
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

    // Shown in place of the home history / favourites while they cannot be loaded, null once
    // there is real content to display.
    val historyMessage = when {
        !retroLoaded -> "Reading RetroArch history..."
        !retroArchAvailable -> "RetroArch is not installed - your last played games will show up here"
        !retroHasPlaylistAccess -> "Grant playlist access in the GAMES tab to see your history here"
        else -> null
    }

    val favouriteMessage = when {
        !retroLoaded -> "Reading RetroArch favourites..."
        !retroArchAvailable -> "RetroArch is not installed - your favourite games will show up here"
        !retroHasPlaylistAccess -> "Grant playlist access in the GAMES tab to see your favourites here"
        else -> null
    }

    // The fullscreen screenshots viewer navigates a flat index over the open tab's images,
    // wrapping at both ends. D-pad left / right arrive here first (preview), so no tile behind
    // the viewer can snipe the focus while it is open.
    val screenshotImages = when (selectedScreenshotsTab) {
        ScreenshotsTab.IMAGES -> galleryImages
        ScreenshotsTab.SCREENSHOTS -> savedScreenshots
    }

    fun showViewerImage(step: Int) {
        val count = screenshotImages.size
        if (count == 0) {
            screenshotViewerIndex = -1
            return
        }
        screenshotViewerIndex = (screenshotViewerIndex + step + count) % count
    }

    fun closeViewer() {
        screenshotViewerIndex = -1
        // The gallery grabs its focus back, like it does after the drawer closes.
        focusEpoch++
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
                // While the fullscreen viewer of the SCREENSHOTS menu is open it owns every
                // key: left / right browse its image list (consumed here, before focus, so
                // nothing behind the viewer moves focus) and Back closes it.
                if (screenshotViewerIndex >= 0 && selectedMenu == DrawerMenuItem.SCREENSHOTS) {
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP ->
                            showViewerImage(-1)

                        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN ->
                            showViewerImage(1)

                        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> closeViewer()

                        else -> Unit
                    }
                    return@onPreviewKeyEvent true
                }
                when (event.nativeKeyEvent.keyCode) {
                    // MENU or Y toggles the drawer above the launcher.
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> {
                        drawerOpen = !drawerOpen
                        if (!drawerOpen) focusEpoch++
                        true
                    }

                    // L1 / R1 flip the tabs of the HOME, SCREENSHOTS and SETTINGS screens while
                    // the drawer is closed.
                    KeyEvent.KEYCODE_BUTTON_L1 -> {
                        if (drawerOpen) {
                            false
                        } else when (selectedMenu) {
                            DrawerMenuItem.HOME -> {
                                selectedTab = selectedTab.previous()
                                true
                            }

                            DrawerMenuItem.SCREENSHOTS -> {
                                selectedScreenshotsTab =
                                    cycleScreenshotsTab(selectedScreenshotsTab, forward = false)
                                true
                            }

                            DrawerMenuItem.SETTINGS -> {
                                selectedSettingsTab = cycleSettingsTab(selectedSettingsTab, forward = false)
                                true
                            }

                            else -> false
                        }
                    }

                    KeyEvent.KEYCODE_BUTTON_R1 -> {
                        if (drawerOpen) {
                            false
                        } else when (selectedMenu) {
                            DrawerMenuItem.HOME -> {
                                selectedTab = selectedTab.next()
                                true
                            }

                            DrawerMenuItem.SCREENSHOTS -> {
                                selectedScreenshotsTab =
                                    cycleScreenshotsTab(selectedScreenshotsTab, forward = true)
                                true
                            }

                            DrawerMenuItem.SETTINGS -> {
                                selectedSettingsTab = cycleSettingsTab(selectedSettingsTab, forward = true)
                                true
                            }

                            else -> false
                        }
                    }

                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> {
                        when {
                            drawerOpen -> {
                                drawerOpen = false
                                focusEpoch++
                            }
                            selectedMenu != DrawerMenuItem.HOME -> selectedMenu = DrawerMenuItem.HOME
                            selectedTab != LauncherTab.HOME -> selectedTab = LauncherTab.HOME
                        }
                        true
                    }

                    else -> false
                }
            },
    ) {
        Crossfade(targetState = ambientSnap, label = "ambientBackground") { snap ->
            if (snap != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // The in-game screenshot fills the screen; a dim scrim keeps the launcher
                    // tiles readable on top of it. The blur only renders on Android 12+.
                    Image(
                        bitmap = snap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(40.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ConsoleBackground.copy(alpha = 0.72f)),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            StatusBar(
                tabTitles = tabsForMenu(selectedMenu),
                selectedTabTitle = when (selectedMenu) {
                    DrawerMenuItem.HOME -> selectedTab.title
                    DrawerMenuItem.SCREENSHOTS -> selectedScreenshotsTab.title
                    DrawerMenuItem.SETTINGS -> selectedSettingsTab.title
                    else -> null
                },
                onTabSelected = { title ->
                    when (selectedMenu) {
                        DrawerMenuItem.SCREENSHOTS -> {
                            selectedScreenshotsTab = ScreenshotsTab.entries.first { it.title == title }
                        }

                        DrawerMenuItem.SETTINGS -> {
                            selectedSettingsTab = SettingsTab.entries.first { it.title == title }
                        }

                        else -> selectedTab = LauncherTab.entries.first { it.title == title }
                    }
                },
                onOpenDrawer = { drawerOpen = true },
                wordmark = if (savedName.isBlank()) "Hi" else "Hi ${savedName.trim()}",
            )

            Box(modifier = Modifier.weight(1f)) {
                when (selectedMenu) {
                    DrawerMenuItem.HOME -> when (selectedTab) {
                        LauncherTab.HOME -> LauncherGrid(
                            rows = homeRows(
                                recents = recents,
                                historyGames = historyGames,
                                historyMessage = historyMessage,
                                favouriteGames = favouriteGames,
                                favouriteMessage = favouriteMessage,
                            ),
                            onLaunchApp = ::launch,
                            onLaunchGame = ::launchGame,
                            onLoadThumbnail = { path -> retroRepository.loadThumbnail(path) },
                            onLoadBoxArt = { name -> retroRepository.loadBoxArt(name) },
                            onGameHover = { hoveredGame = it },
                            autoFocusFirstItem = true,
                            focusEpoch = focusEpoch,
                        )

                        LauncherTab.GAMES -> LauncherGameSystems(
                            games = retroGames,
                            error = retroError,
                            loaded = retroLoaded,
                            available = retroArchAvailable,
                            hasPlaylistAccess = retroHasPlaylistAccess,
                            loading = retroLoading,
                            onLaunchGame = ::launchGame,
                            onLoadThumbnail = { path -> retroRepository.loadThumbnail(path) },
                            onLoadBoxArt = { name -> retroRepository.loadBoxArt(name) },
                            onGameHover = { hoveredGame = it },
                            onAction = ::handleGameAction,
                            autoFocusFirstItem = true,
                            focusEpoch = focusEpoch,
                        )

                        LauncherTab.RECENT -> LauncherGrid(
                            rows = recentRows(recents),
                            onLaunchApp = ::launch,
                            autoFocusFirstItem = true,
                            focusEpoch = focusEpoch,
                        )

                        LauncherTab.ALL_APPS -> LauncherGrid(
                            rows = allAppsRows(apps),
                            onLaunchApp = ::launch,
                            autoFocusFirstItem = true,
                            focusEpoch = focusEpoch,
                        )
                    }

                    DrawerMenuItem.SCREENSHOTS -> ScreenshotsScreen(
                        tab = selectedScreenshotsTab,
                        playlistImages = galleryImages,
                        savedScreenshots = savedScreenshots,
                        loading = (!galleryLoaded || galleryLoading) &&
                            retroArchAvailable && retroHasPlaylistAccess,
                        available = retroArchAvailable,
                        hasPlaylistAccess = retroHasPlaylistAccess,
                        viewerIndex = screenshotViewerIndex,
                        onLoadImage = { uri -> retroRepository.loadGalleryImage(uri) },
                        onLoadFullImage = { uri -> retroRepository.loadSnap(uri) },
                        onOpenViewer = { screenshotViewerIndex = it },
                        onViewerClosed = { screenshotViewerIndex = -1 },
                        onAction = ::handleGameAction,
                        autoFocusFirstItem = true,
                        focusEpoch = focusEpoch,
                    )

                    DrawerMenuItem.STEAM -> EmptyScreen(
                        title = "STEAM",
                        message = if (steamLinkMissing) {
                            "Steam Link is not installed - install it to stream from your PC"
                        } else {
                            "Opening Steam Link..."
                        },
                    )

                    DrawerMenuItem.SETTINGS -> LauncherSettings(
                        tab = selectedSettingsTab,
                        savedName = savedName,
                        accentOption = accentOption,
                        retroArchAvailable = retroArchAvailable,
                        hasPlaylistAccess = retroHasPlaylistAccess,
                        actionBubbleEnabled = actionBubble,
                        overlayPermissionGranted = overlayGranted,
                        ambientModeEnabled = ambientMode,
                        onToggleActionBubble = ::toggleActionBubble,
                        onToggleAmbientMode = ::toggleAmbientMode,
                        onCycleAccent = ::cycleAccentOption,
                        onAction = ::handleGameAction,
                        onSaveName = ::saveName,
                        autoFocusFirstItem = true,
                        focusEpoch = focusEpoch,
                    )
                }
            }

            HintBar()
        }

        DrawerOverlay(
            visible = drawerOpen,
            selectedItem = selectedMenu,
            onItemSelected = ::handleMenu,
            onDismiss = {
                drawerOpen = false
                focusEpoch++
            },
            userName = savedName,
            modifier = Modifier.fillMaxSize(),
        )
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
        Hint("KEYS", "SEARCH")
        Hint("MENU / Y", "DRAWER")
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

private fun homeRows(
    recents: List<AppEntry>,
    historyGames: List<RetroGameEntry>,
    historyMessage: String?,
    favouriteGames: List<RetroGameEntry>,
    favouriteMessage: String?,
): List<LauncherRow> = buildList {
    add(LauncherRow.Section("CONTINUE"))
    if (recents.isEmpty()) {
        add(LauncherRow.Message("Nothing here yet - launch an app to build your history"))
    } else {
        recents.take(4).forEach { add(LauncherRow.App("continue_${it.packageName}", it)) }
    }
    add(LauncherRow.Section("HISTORY"))
    if (historyGames.isEmpty()) {
        add(LauncherRow.Message(historyMessage ?: "No games played yet - start one from the GAMES tab"))
    } else {
        add(LauncherRow.GameRow("home_history", historyGames, verticalCards = true))
    }
    add(LauncherRow.Section("FAVOURITES"))
    if (favouriteGames.isEmpty()) {
        add(
            LauncherRow.Message(
                favouriteMessage ?: "No favourites yet - star games in RetroArch and they will show up here",
            ),
        )
    } else {
        add(LauncherRow.GameRow("home_favourites", favouriteGames, verticalCards = true))
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

/** The tab strip the top bar shows for a drawer menu; the other menus have no tabs. */
private fun tabsForMenu(menu: DrawerMenuItem): List<String> = when (menu) {
    DrawerMenuItem.HOME -> LauncherTab.entries.map { it.title }
    DrawerMenuItem.SCREENSHOTS -> ScreenshotsTab.entries.map { it.title }
    DrawerMenuItem.SETTINGS -> SettingsTab.entries.map { it.title }
    else -> emptyList()
}

private fun LauncherTab.next(): LauncherTab {
    val values = LauncherTab.entries
    return values[(ordinal + 1) % values.size]
}

private fun LauncherTab.previous(): LauncherTab {
    val values = LauncherTab.entries
    return values[(ordinal - 1 + values.size) % values.size]
}

/** Flips the SCREENSHOTS menu between its IMAGES and SCREENSHOTS tabs. */
private fun cycleScreenshotsTab(current: ScreenshotsTab, forward: Boolean): ScreenshotsTab {
    val values = ScreenshotsTab.entries
    return if (forward) {
        values[(current.ordinal + 1) % values.size]
    } else {
        values[(current.ordinal - 1 + values.size) % values.size]
    }
}

private const val ACTION_SHOW_ARTWORK = "games_show_artwork"
