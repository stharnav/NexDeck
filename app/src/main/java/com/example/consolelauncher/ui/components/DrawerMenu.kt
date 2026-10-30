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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.consolelauncher.ui.theme.ConsoleAccent
import com.example.consolelauncher.ui.theme.ConsoleOnAccent
import com.example.consolelauncher.ui.theme.ConsoleStroke
import com.example.consolelauncher.ui.theme.ConsoleSurface
import com.example.consolelauncher.ui.theme.ConsoleTextPrimary
import com.example.consolelauncher.ui.theme.ConsoleTextSecondary
import kotlinx.coroutines.delay

/** The entries of the drawer menu on the left side of the launcher. */
enum class DrawerMenuItem(val title: String) {
    HOME("HOME"),
    SETUP("SETUP"),
    SCREENSHOTS("SCREENSHOTS"),
    STEAM("STEAM"),
    SETTINGS("SETTINGS"),
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

/**
 * The collapsible drawer as an overlay above the launcher: a scrim over the whole screen with
 * the menu panel sliding in from the left edge. Nothing underneath is drawn differently, the
 * launcher simply stays where it was.
 */
@Composable
fun DrawerOverlay(
    visible: Boolean,
    selectedItem: DrawerMenuItem,
    onItemSelected: (DrawerMenuItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Attached to the drawer entry of the screen that is open, so opening the drawer puts the
    // D-pad selection on the current link instead of always on the first one (HOME).
    val currentItemRequester = remember { FocusRequester() }

    Box(modifier) {
        // The scrim dismisses the drawer on touch. It deliberately avoids clickable() so it
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

            DrawerMenu(
                selectedItem = selectedItem,
                onItemSelected = onItemSelected,
                selectedItemRequester = currentItemRequester,
            )
        }
    }
}

/**
 * The console style drawer panel. It is focusable so D-pad or arrow keys can move between the
 * menu entries and Enter selects them.
 */
@Composable
fun DrawerMenu(
    selectedItem: DrawerMenuItem,
    onItemSelected: (DrawerMenuItem) -> Unit,
    modifier: Modifier = Modifier,
    selectedItemRequester: FocusRequester? = null,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(220.dp)
            .shadow(elevation = 24.dp, shape = RoundedCornerShape(0.dp), spotColor = Color.Black)
            .background(ConsoleSurface)
            .padding(vertical = 18.dp, horizontal = 12.dp),
    ) {
        Text(
            text = "MENU",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = ConsoleTextSecondary,
            modifier = Modifier
                .padding(start = 12.dp)
                .padding(bottom = 14.dp),
        )

        DrawerMenuItem.entries.forEachIndexed { index, item ->
            DrawerItem(
                item = item,
                selected = item == selectedItem,
                // The selection highlight and the D-pad focus start on the same entry, so only
                // one item ever appears lit.
                focusRequester = if (item == selectedItem) selectedItemRequester else null,
                onClick = { onItemSelected(item) },
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun DrawerItem(
    item: DrawerMenuItem,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // The entry of the screen that is actually being shown, plus whichever entry the D-pad is on.
    val accent = selected || focused

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (accent) ConsoleAccent else ConsoleSurface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) ConsoleTextPrimary else ConsoleStroke,
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .let { modifier -> focusRequester?.let { modifier.focusRequester(it) } ?: modifier }
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = if (accent) ConsoleOnAccent else ConsoleTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
