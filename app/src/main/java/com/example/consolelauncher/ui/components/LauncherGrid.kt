package com.example.consolelauncher.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.consolelauncher.data.AppEntry
import com.example.consolelauncher.data.RetroGameEntry
import com.example.consolelauncher.ui.theme.ConsoleAccent
import com.example.consolelauncher.ui.theme.ConsoleStroke
import com.example.consolelauncher.ui.theme.ConsoleSurface
import com.example.consolelauncher.ui.theme.ConsoleTextPrimary
import com.example.consolelauncher.ui.theme.ConsoleTextSecondary
import kotlinx.coroutines.delay

/** A single row of the launcher grid: a section title, a tile or a hint. */
sealed interface LauncherRow {
    /** [key] keeps the grid item stable even if two sections share a title. */
    data class Section(val title: String, val key: String = title) : LauncherRow
    data class Letter(val letter: String) : LauncherRow

    /** [key] has to be unique within the screen, an app can show up in more than one section. */
    data class App(val key: String, val entry: AppEntry) : LauncherRow

    /** A horizontally scrollable row of games, e.g. the RetroArch history on the home screen. */
    data class GameRow(
        val key: String,
        val games: List<RetroGameEntry>,
        /** Tiles stack the artwork above a divider and the game name instead of a wide card. */
        val verticalCards: Boolean = false,
    ) : LauncherRow

    data class Message(val text: String) : LauncherRow

    /** A focusable full width button, used for permission and refresh actions. */
    data class Action(val key: String, val label: String, val description: String) : LauncherRow
}

/** The key of the row that receives the focus when the tab opens, if any. */
private val LauncherRow.focusKey: String?
    get() = when (this) {
        is LauncherRow.App -> key
        is LauncherRow.GameRow -> games.firstOrNull()?.gridKey
        is LauncherRow.Action -> key
        is LauncherRow.Section, is LauncherRow.Letter, is LauncherRow.Message -> null
    }

/**
 * The grid of app and game tiles. Section titles and alphabet letters span the full width so that
 * the focus traversal of a D-pad naturally skips over them.
 */
@Composable
fun LauncherGrid(
    rows: List<LauncherRow>,
    onLaunchApp: (AppEntry) -> Unit,
    onLaunchGame: (RetroGameEntry) -> Unit = {},
    onAction: (LauncherRow.Action) -> Unit = {},
    onLoadThumbnail: suspend (String) -> ImageBitmap? = { null },
    onLoadBoxArt: suspend (String) -> ImageBitmap? = { null },
    autoFocusFirstItem: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val firstItemRequester = remember { FocusRequester() }
    val firstFocusableKey = remember(rows) { rows.firstNotNullOfOrNull { it.focusKey } }

    LaunchedEffect(firstFocusableKey) {
        if (!autoFocusFirstItem || firstFocusableKey == null) return@LaunchedEffect
        repeat(10) {
            delay(150)
            if (firstItemRequester.requestFocus()) return@LaunchedEffect
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        gridItems(
            items = rows,
            key = { row ->
                when (row) {
                    is LauncherRow.Section -> "section_${row.key}"
                    is LauncherRow.Letter -> "letter_${row.letter}"
                    is LauncherRow.App -> row.key
                    is LauncherRow.GameRow -> row.key
                    is LauncherRow.Message -> "message_${row.text}"
                    is LauncherRow.Action -> row.key
                }
            },
            span = { row ->
                when (row) {
                    is LauncherRow.App -> GridItemSpan(1)
                    else -> GridItemSpan(maxLineSpan)
                }
            },
        ) { row ->
            val isFirstFocusable = autoFocusFirstItem && row.focusKey != null && row.focusKey == firstFocusableKey
            when (row) {
                is LauncherRow.Section -> SectionTitle(row.title)
                is LauncherRow.Letter -> LetterTitle(row.letter)
                is LauncherRow.Message -> MessageText(row.text)
                is LauncherRow.Action -> ActionTile(
                    label = row.label,
                    description = row.description,
                    onClick = { onAction(row) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .thenIf(isFirstFocusable) { focusRequester(firstItemRequester) },
                )
                is LauncherRow.App -> GameCard(
                    entry = row.entry,
                    onClick = { onLaunchApp(row.entry) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CARD_HEIGHT)
                        .thenIf(isFirstFocusable) { focusRequester(firstItemRequester) },
                )
                is LauncherRow.GameRow -> GameSystemRow(
                    games = row.games,
                    onLaunchGame = onLaunchGame,
                    onLoadThumbnail = onLoadThumbnail,
                    onLoadBoxArt = onLoadBoxArt,
                    modifier = Modifier.fillMaxWidth(),
                    firstItemRequester = if (
                        autoFocusFirstItem &&
                        row.games.firstOrNull()?.gridKey == firstFocusableKey
                    ) {
                        firstItemRequester
                    } else {
                        null
                    },
                    cardWidth = if (row.verticalCards) HISTORY_CARD_WIDTH else GAME_CARD_WIDTH,
                    cardHeight = if (row.verticalCards) HISTORY_CARD_HEIGHT else GAME_CARD_HEIGHT,
                    verticalCards = row.verticalCards,
                )
            }
        }
    }
}

private inline fun Modifier.thenIf(condition: Boolean, crossinline block: Modifier.() -> Modifier): Modifier =
    if (condition) this.block() else this

/**
 * The GAMES tab: each system (RetroArch playlist) is a single horizontally scrollable row of
 * game cards. A gamepad can dash through a system's games left/right and move between systems
 * up/down.
 */
@Composable
fun LauncherGameSystems(
    games: List<RetroGameEntry>,
    error: String?,
    loaded: Boolean,
    available: Boolean,
    hasPlaylistAccess: Boolean,
    loading: Boolean,
    onLaunchGame: (RetroGameEntry) -> Unit = {},
    onLoadThumbnail: suspend (String) -> ImageBitmap? = { null },
    onLoadBoxArt: suspend (String) -> ImageBitmap? = { null },
    onAction: (LauncherRow.Action) -> Unit = {},
    autoFocusFirstItem: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val firstItemRequester = remember { FocusRequester() }
    val firstGame = remember(games) {
        games.groupBy { it.playlistName }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .values
            .asSequence()
            .flatten()
            .firstOrNull()
    }
    val firstGameKey = firstGame?.gridKey

    LaunchedEffect(firstGameKey) {
        if (!autoFocusFirstItem || firstGameKey == null) return@LaunchedEffect
        repeat(10) {
            delay(150)
            if (firstItemRequester.requestFocus()) return@LaunchedEffect
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            !loaded || (loading && games.isEmpty()) -> {
                item(key = "loading") {
                    MessageText("Reading RetroArch playlists...")
                }
            }

            !available -> {
                item(key = "unavailable") {
                    MessageText(
                        "RetroArch is not installed. Install RetroArch to play your ROM collection from here.",
                    )
                }
            }

            !hasPlaylistAccess -> {
                item(key = "access_message") {
                    MessageText(
                        "Grant access to the folder where RetroArch keeps its playlists to list your games here.",
                    )
                }
                item(key = "grant_access") {
                    ActionTile(
                        label = "GRANT PLAYLIST ACCESS",
                        description = "Pick the RetroArch folder that contains the playlists folder",
                        onClick = {
                            onAction(
                                LauncherRow.Action(
                                    key = ACTION_GRANT_PLAYLISTS,
                                    label = "GRANT PLAYLIST ACCESS",
                                    description = "Pick the RetroArch folder that contains the playlists folder",
                                ),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .thenIf(firstGameKey == null) { focusRequester(firstItemRequester) },
                    )
                }
            }

            games.isEmpty() -> {
                item(key = "empty") {
                    MessageText(error ?: "No games found in the selected folder.")
                }
                item(key = "choose_folder") {
                    ActionTile(
                        label = "CHOOSE ANOTHER FOLDER",
                        description = "Pick the folder that contains your RetroArch .lpl playlists",
                        onClick = {
                            onAction(
                                LauncherRow.Action(
                                    key = ACTION_GRANT_PLAYLISTS,
                                    label = "CHOOSE ANOTHER FOLDER",
                                    description = "Pick the folder that contains your RetroArch .lpl playlists",
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "refresh") {
                    ActionTile(
                        label = "REFRESH",
                        description = "Scan the selected folder again",
                        onClick = {
                            onAction(
                                LauncherRow.Action(
                                    key = ACTION_REFRESH_GAMES,
                                    label = "REFRESH",
                                    description = "Scan the selected folder again",
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            else -> {
                if (error != null) {
                    item(key = "error") { MessageText(error) }
                }
                games.groupBy { it.playlistName }
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    .forEach { (playlist, entries) ->
                        item(key = "section_$playlist") {
                            SectionTitle(playlist.uppercase())
                        }
                        item(key = "row_$playlist") {
                            GameSystemRow(
                                games = entries,
                                onLaunchGame = onLaunchGame,
                                onLoadThumbnail = onLoadThumbnail,
                                onLoadBoxArt = onLoadBoxArt,
                                modifier = Modifier.fillMaxWidth(),
                                firstItemRequester = if (
                                    firstGameKey == entries.firstOrNull()?.gridKey
                                ) {
                                    firstItemRequester
                                } else {
                                    null
                                },
                            )
                        }
                    }
                item(key = "change_folder") {
                    ActionTile(
                        label = "CHANGE PLAYLIST FOLDER",
                        description = "Scan a different RetroArch playlists folder",
                        onClick = {
                            onAction(
                                LauncherRow.Action(
                                    key = ACTION_CHANGE_PLAYLISTS,
                                    label = "CHANGE PLAYLIST FOLDER",
                                    description = "Scan a different RetroArch playlists folder",
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** One horizontally scrollable row of games belonging to a single system/playlist. */
@Composable
private fun GameSystemRow(
    games: List<RetroGameEntry>,
    onLaunchGame: (RetroGameEntry) -> Unit,
    onLoadThumbnail: suspend (String) -> ImageBitmap?,
    onLoadBoxArt: suspend (String) -> ImageBitmap?,
    modifier: Modifier = Modifier,
    firstItemRequester: FocusRequester? = null,
    cardWidth: Dp = GAME_CARD_WIDTH,
    cardHeight: Dp = GAME_CARD_HEIGHT,
    verticalCards: Boolean = false,
) {
    val firstKey = games.firstOrNull()?.gridKey

    LazyRow(
        modifier = modifier.offset(x = -GAME_ROW_BLEED),
        // The row clips along its scroll axis, which would cut the focused card's scale and
        // glow. Bleed room on both ends plus a matching shift left keep the cards aligned with
        // the section titles while the focus effects stay visible.
        contentPadding = PaddingValues(horizontal = GAME_ROW_BLEED),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Keys embed the index so a data source that yields the same game twice (which the
        // history playlist occasionally does) cannot crash the row with duplicate keys.
        lazyItemsIndexed(items = games, key = { index, entry -> "${entry.gridKey}_$index" }) { _, entry ->
            val isFirst = firstItemRequester != null && firstKey == entry.gridKey
            RetroGameCard(
                entry = entry,
                onClick = { onLaunchGame(entry) },
                thumbnailLoader = onLoadThumbnail,
                boxArtLoader = onLoadBoxArt,
                vertical = verticalCards,
                modifier = Modifier
                    .width(cardWidth)
                    .height(cardHeight)
                    .thenIf(isFirst) { focusRequester(firstItemRequester!!) },
            )
        }
    }
}

@Composable
private fun ActionTile(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ACTION_CORNER)

    Column(
        modifier = modifier
            .scale(if (focused) 1.01f else 1f)
            .clip(shape)
            .background(if (focused) ConsoleAccent.copy(alpha = 0.18f) else ConsoleSurface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) ConsoleAccent else ConsoleStroke,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.isSelectKey()) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = if (focused) ConsoleAccent else ConsoleTextPrimary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = ConsoleTextSecondary,
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        color = ConsoleTextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun LetterTitle(letter: String) {
    Text(
        text = letter,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = ConsoleAccent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    )
}

@Composable
private fun MessageText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = ConsoleTextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    )
}

private const val COLUMNS = 4
private val CARD_HEIGHT = 100.dp

/** Game tiles are taller so the box art is shown as a cover, not as a tiny icon. */
private val GAME_CARD_HEIGHT = 138.dp
private val ACTION_CORNER = 12.dp

private val GAME_CARD_WIDTH = 220.dp

/** History tiles stack the artwork above a divider and the game name. */
private val HISTORY_CARD_WIDTH = 220.dp
private val HISTORY_CARD_HEIGHT = 240.dp

/** Room at the row ends so the focused card's scale and glow are not clipped by the LazyRow. */
private val GAME_ROW_BLEED = 16.dp

internal const val ACTION_GRANT_PLAYLISTS = "games_grant_playlists"
internal const val ACTION_CHANGE_PLAYLISTS = "games_change_playlists"
internal const val ACTION_REFRESH_GAMES = "games_refresh"
