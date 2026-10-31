package com.example.consolelauncher.ui.components

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.consolelauncher.ui.theme.ConsoleAccent
import com.example.consolelauncher.ui.theme.ConsoleStroke
import com.example.consolelauncher.ui.theme.ConsoleSurface
import com.example.consolelauncher.ui.theme.ConsoleTextPrimary
import com.example.consolelauncher.ui.theme.ConsoleTextSecondary
import kotlinx.coroutines.delay

/** The tabs of the SETTINGS screen. */
enum class SettingsTab(val title: String) {
    USER("USER"),
    RETROARCH("RETROARCH"),
}

private fun SettingsTab.next(): SettingsTab {
    val values = SettingsTab.entries
    return values[(ordinal + 1) % values.size]
}

private fun SettingsTab.previous(): SettingsTab {
    val values = SettingsTab.entries
    return values[(ordinal - 1 + values.size) % values.size]
}

internal fun cycleSettingsTab(from: SettingsTab, forward: Boolean): SettingsTab =
    if (forward) from.next() else from.previous()

/**
 * The SETTINGS screen: the same console look as the home tabs, one section per tab. USER asks
 * for and saves the player's display name, RETROARCH keeps the playlist folder controls that
 * used to sit at the bottom of the GAMES tab plus the ambient mode switch.
 */
@Composable
fun LauncherSettings(
    tab: SettingsTab,
    savedName: String,
    retroArchAvailable: Boolean,
    hasPlaylistAccess: Boolean,
    ambientModeEnabled: Boolean,
    onToggleAmbientMode: (Boolean) -> Unit,
    onAction: (LauncherRow.Action) -> Unit,
    onSaveName: (String) -> Unit,
    autoFocusFirstItem: Boolean = false,
    focusEpoch: Int = 0,
    modifier: Modifier = Modifier,
) {
    var nameValue by remember(tab) { mutableStateOf(savedName) }
    val firstItemRequester = remember { FocusRequester() }

    // On entry and on every tab switch the screen grabs the D-pad focus onto its first control.
    LaunchedEffect(tab, autoFocusFirstItem, focusEpoch) {
        if (!autoFocusFirstItem) return@LaunchedEffect
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
        when (tab) {
            SettingsTab.USER -> {
                item(key = "user_section") {
                    SectionTitle("YOUR NAME")
                }
                item(key = "user_value") {
                    MessageText(
                        if (savedName.isBlank()) {
                            "No name saved yet. Bring up the on-screen keyboard on the field below and select SAVE NAME."
                        } else {
                            "Signed in as $savedName"
                        },
                    )
                }
                item(key = "user_field") {
                    SettingsNameField(
                        value = nameValue,
                        onValueChange = { nameValue = it },
                        onDone = onSaveName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .thenIf(autoFocusFirstItem) { focusRequester(firstItemRequester) },
                    )
                }
                item(key = "user_save") {
                    ActionTile(
                        label = "SAVE NAME",
                        description = "Store your name on this device",
                        onClick = {
                            onSaveName(nameValue)
                            nameValue = nameValue.trim()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SettingsTab.RETROARCH -> {
                item(key = "retro_section") {
                    SectionTitle("RETROARCH")
                }
                if (!retroArchAvailable) {
                    item(key = "retro_unavailable") {
                        MessageText(
                            "RetroArch is not installed. Install RetroArch, then the playlist folder options reappear here.",
                        )
                    }
                } else {
                    item(key = "retro_access") {
                        MessageText(
                            if (hasPlaylistAccess) {
                                "Playlist folder access is granted. Use the button below to scan a different folder."
                            } else {
                                "Playlist access is not granted yet. The GAMES tab asks for it the first time you open it."
                            },
                        )
                    }
                    item(key = "retro_change_folder") {
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .thenIf(autoFocusFirstItem) { focusRequester(firstItemRequester) },
                        )
                    }
                    item(key = "retro_ambient") {
                        SettingsToggleTile(
                            label = "AMBIENT MODE",
                            description = "Light up the room while a game runs (feature coming soon)",
                            checked = ambientModeEnabled,
                            onToggle = onToggleAmbientMode,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A rows style switch tile: label and description on the left, a console colored pill switch on
 * the right. Enter or the gamepad A button (and a tap) flips the state.
 */
@Composable
private fun SettingsToggleTile(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ACTION_CORNER)

    Row(
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
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.isSelectKey()) {
                    onToggle(!checked)
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
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
        Spacer(Modifier.width(12.dp))
        SwitchPill(checked = checked, focused = focused)
    }
}

/** The small track and thumb that visualize the on/off state of a [SettingsToggleTile]. */
@Composable
private fun SwitchPill(checked: Boolean, focused: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    checked -> ConsoleAccent
                    focused -> ConsoleAccent.copy(alpha = 0.3f)
                    else -> ConsoleStroke
                },
            )
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (checked) ConsoleSurface else ConsoleTextSecondary),
        )
    }
}

/**
 * The user name input. Same read-only gate as the game search bar: tab entry auto-focuses the
 * field, but a focused editable field would pop the on-screen keyboard on its own, so the field
 * stays read-only until the user presses on it (touch) or types on a hardware keyboard.
 */
@Composable
private fun SettingsNameField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var interactive by remember { mutableStateOf(false) }
    var keyboardUp by remember { mutableStateOf(false) }
    val clickFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val hasHardwareKeyboard = LocalConfiguration.current.keyboard != Configuration.KEYBOARD_NOKEYS
    val shape = RoundedCornerShape(ACTION_CORNER)

    fun showKeyboard() {
        if (!hasHardwareKeyboard) {
            keyboard?.show()
        }
        keyboardUp = true
    }

    fun hideKeyboard() {
        keyboard?.hide()
        keyboardUp = false
    }

    fun toggleKeyboard() {
        if (keyboardUp) hideKeyboard() else showKeyboard()
    }

    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (focused) ConsoleAccent.copy(alpha = 0.16f) else ConsoleSurface)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) ConsoleAccent else ConsoleStroke,
                    shape = shape,
                )
                .onFocusChanged { state ->
                    focused = state.isFocused
                    if (!state.isFocused) {
                        interactive = false
                        hideKeyboard()
                    }
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "NAME",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = if (focused) ConsoleAccent else ConsoleTextSecondary,
            )
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.take(NAME_MAX_LENGTH)) },
                readOnly = !interactive,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = ConsoleTextPrimary),
                cursorBrush = SolidColor(ConsoleAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    hideKeyboard()
                    onDone(value.trim())
                }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(clickFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                            return@onPreviewKeyEvent false
                        }
                        when (event.nativeKeyEvent.keyCode) {
                            // Keep the D-pad moving up/down through the settings rows.
                            KeyEvent.KEYCODE_DPAD_DOWN -> focusManager.moveFocus(FocusDirection.Down)
                            KeyEvent.KEYCODE_DPAD_UP -> focusManager.moveFocus(FocusDirection.Up)
                            else -> {
                                val ch = event.nativeKeyEvent.getUnicodeChar()
                                if (ch >= ' '.code) {
                                    if (!interactive) {
                                        interactive = true
                                        onValueChange((value + ch.toChar()).take(NAME_MAX_LENGTH))
                                    }
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                    },
                decorationBox = { innerTextField ->
                    if (value.isBlank()) {
                        Text(
                            text = "TAP TO TYPE YOUR NAME...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ConsoleTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                },
            )
            if (keyboardUp) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "⌨",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ConsoleTextSecondary,
                )
            }
        }

        // Intercept taps so they cannot reach the text field directly; the tap handler owns the
        // keyboard up/down behavior, like on the search bar.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable {
                    if (!focused) clickFocus.requestFocus()
                    interactive = true
                    toggleKeyboard()
                },
        )
    }
}

/** Names are capped so a stuck repeating key cannot fill the field. */
private const val NAME_MAX_LENGTH = 10
