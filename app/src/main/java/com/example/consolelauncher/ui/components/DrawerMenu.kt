package com.example.consolelauncher.ui.components

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.consolelauncher.R
import com.example.consolelauncher.ui.theme.ConsoleAccent
import com.example.consolelauncher.ui.theme.ConsoleOnAccent
import com.example.consolelauncher.ui.theme.ConsoleStroke
import com.example.consolelauncher.ui.theme.ConsoleSurface
import com.example.consolelauncher.ui.theme.ConsoleTextPrimary
import com.example.consolelauncher.ui.theme.ConsoleTextSecondary
import kotlinx.coroutines.delay

/** The entries of the sidebar menu on the left side of the launcher. */
enum class DrawerMenuItem(val title: String, val iconRes: Int) {
    HOME("HOME", R.drawable.menu_home),
    SCREENSHOTS("SCREENSHOTS", R.drawable.menu_screenshots),
    STEAM("STEAM", R.drawable.menu_steam),
    SETTINGS("SETTINGS", R.drawable.menu_settings),
}

/** The Steam Link app the STEAM menu opens. */
const val STEAM_LINK_PACKAGE = "com.valvesoftware.steamlink"

/** Launches the Steam Link app, returning false when it is not installed. */
fun Context.launchSteamLink(): Boolean {
    val intent = packageManager.getLaunchIntentForPackage(STEAM_LINK_PACKAGE) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    return true
}

/** The little vector icon of a [DrawerMenuItem], drawn at [iconSize] dp. */
@Composable
private fun SidebarIcon(item: DrawerMenuItem, tint: Color, iconSize: Dp) {
    Image(
        painter = painterResource(item.iconRes),
        contentDescription = item.title,
        // The icons ship as neutral black vectors; the tint carries the accent / dimmed state.
        colorFilter = ColorFilter.tint(tint),
        modifier = Modifier.size(iconSize, iconSize),
    )
}

/**
 * The collapsible sidebar as an overlay above the launcher: a scrim over the whole screen with
 * the sidebar panel sliding in from the left edge. Nothing underneath is drawn differently, the
 * launcher simply stays where it was.
 */
@Composable
fun DrawerOverlay(
    visible: Boolean,
    selectedItem: DrawerMenuItem,
    onItemSelected: (DrawerMenuItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    userName: String = "",
) {
    // Attached to the sidebar entry of the screen that is open, so opening the sidebar puts the
    // D-pad selection on the current link instead of always on the first one (HOME).
    val currentItemRequester = remember { FocusRequester() }

    Box(modifier) {
        // The scrim dismisses the sidebar on touch. It deliberately avoids clickable() so it
        // never joins the D-pad focus search.
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(DRAWER_ANIM_MILLIS)),
            exit = fadeOut(animationSpec = tween(DRAWER_ANIM_MILLIS)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
        }

        // The panel slides in above the launcher from the left edge.
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(animationSpec = tween(DRAWER_ANIM_MILLIS)) { -it },
            exit = slideOutHorizontally(animationSpec = tween(DRAWER_ANIM_MILLIS)) { -it },
        ) {
            LaunchedEffect(currentItemRequester) {
                // The panel takes a moment to attach; poll like the grids do for their first card.
                repeat(10) {
                    delay(150)
                    if (currentItemRequester.requestFocus()) return@LaunchedEffect
                }
            }

            SidebarMenu(
                selectedItem = selectedItem,
                onItemSelected = onItemSelected,
                userName = userName,
                selectedItemRequester = currentItemRequester,
            )
        }
    }
}

/**
 * The console style sidebar panel. A full height column with the user avatar on top, the
 * navigation entries with their icons underneath and the close hints at the bottom. It is
 * focusable so D-pad or arrow keys can move between the menu entries and Enter selects them.
 */
@Composable
private fun SidebarMenu(
    selectedItem: DrawerMenuItem,
    onItemSelected: (DrawerMenuItem) -> Unit,
    modifier: Modifier = Modifier,
    selectedItemRequester: FocusRequester? = null,
    userName: String = "",
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(SIDEBAR_WIDTH)
            .shadow(elevation = 20.dp, shape = SIDEBAR_SHAPE, spotColor = Color.Black)
            .background(SIDEBAR_BG, SIDEBAR_SHAPE)
            .border(
                width = 1.dp,
                color = ConsoleStroke,
                shape = SIDEBAR_SHAPE,
            ),
    ) {
        // Header: the user avatar and the word mark, cut off by a hairline from the links.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(ConsoleAccent),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.menu_user),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(ConsoleOnAccent),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                // The header greets the user by name, like the top bar does.
                text = userName.trim().uppercase().ifBlank { "PLAYER" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = ConsoleTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ConsoleStroke),
        )

        Text(
            text = "MENU",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = ConsoleTextSecondary,
            modifier = Modifier
                .padding(start = 22.dp)
                .padding(top = 16.dp)
                .padding(bottom = 8.dp),
        )

        DrawerMenuItem.entries.forEach { item ->
            SidebarItem(
                item = item,
                selected = item == selectedItem,
                // The selection highlight and the D-pad focus start on the same entry, so only
                // one item ever appears lit.
                focusRequester = if (item == selectedItem) selectedItemRequester else null,
                onClick = { onItemSelected(item) },
            )
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.weight(1f))

        // Footer: how to leave the sidebar again.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 18.dp, bottom = 20.dp),
        ) {
            SidebarHint("MENU / Y", "TOGGLE")
            Spacer(Modifier.height(5.dp))
            SidebarHint("BACK", "DISMISS")
        }
    }
}

@Composable
private fun SidebarHint(key: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = ConsoleAccent,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = action,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            color = ConsoleTextSecondary,
        )
    }
}

/** One navigation row of the sidebar: icon + label, an accent rail marks the open screen. */
@Composable
private fun SidebarItem(
    item: DrawerMenuItem,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = selected || focused
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(if (accent) ConsoleAccent.copy(alpha = 0.16f) else Color.Transparent)
            .border(
                width = if (focused) 1.5.dp else 0.dp,
                color = if (focused) ConsoleAccent else Color.Transparent,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .let { modifier -> focusRequester?.let { modifier.focusRequester(it) } ?: modifier }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.isSelectKey()) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(onClick = onClick),
    ) {
        if (selected) {
            // The rail: a short accent bar at the very start edge marking the open screen, so
            // "where am I" survives even while the D-pad roams further down the list.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(width = 3.dp, height = 22.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ConsoleAccent),
            )
        }

        // Icon and label are left aligned and vertically centered inside the row: the row
        // fills the box so the centering happens against its full 46 dp height.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SidebarIcon(
                item = item,
                tint = if (accent || selected) ConsoleAccent else ConsoleTextSecondary,
                iconSize = 22.dp,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                // The open screen reads through its rail; D-pad focus lights the ring instead.
                color = if (selected) ConsoleAccent else ConsoleTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The placeholder shown for menu entries that have no screen yet. */
@Composable
fun EmptyScreen(
    title: String,
    message: String = "Nothing here yet",
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = ConsoleTextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = ConsoleTextSecondary,
            )
        }
    }
}

private const val DRAWER_ANIM_MILLIS = 200

private val SIDEBAR_WIDTH = 280.dp

/** The panel runs flush into the screen edge; no rounding on either vertical edge of the right. */
private val SIDEBAR_SHAPE = RectangleShape

/** A whisper darker than the plain surface, so the panel reads as lifted above the launcher. */
private val SIDEBAR_BG = ConsoleSurface.copy(alpha = 0.98f)
