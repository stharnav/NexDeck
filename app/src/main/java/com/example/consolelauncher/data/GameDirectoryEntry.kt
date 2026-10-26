package com.example.consolelauncher.data

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Represents a game/ROM from a user-selected game directory.
 */
data class GameDirectoryEntry(
    val id: String,
    val label: String,
    val romUri: Uri,
    val launchIntent: Intent,
    val thumbnailBitmap: ImageBitmap? = null
) {
    /**
     * Unique key for grid focus management.
     */
    val gridKey: String = "gamedir_$id"
}