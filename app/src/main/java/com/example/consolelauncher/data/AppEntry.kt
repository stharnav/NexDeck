package com.example.consolelauncher.data

import androidx.compose.ui.graphics.ImageBitmap

/** A single launchable application shown on the home screen. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap,
)
