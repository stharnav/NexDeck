package com.example.consolelauncher.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent

/** True for the keys a gamepad or remote uses to confirm a selection. */
internal fun KeyEvent.isSelectKey(): Boolean = when (nativeKeyEvent.keyCode) {
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
    AndroidKeyEvent.KEYCODE_SPACE,
    AndroidKeyEvent.KEYCODE_BUTTON_A,
    -> true

    else -> false
}
