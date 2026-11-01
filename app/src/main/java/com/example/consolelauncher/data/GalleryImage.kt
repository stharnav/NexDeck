package com.example.consolelauncher.data

/**
 * One viewable image for the SCREENSHOTS gallery: either an in-game screenshot that was
 * fetched for a playlist entry (RetroArch `Named_Snaps`) or a screenshot that RetroArch saved
 * during play (its `screenshots` folder).
 */
data class GalleryImage(
    val id: String,
    /** Game label for playlist images, file name for saved screenshots. */
    val title: String,
    /** Section the image is grouped under: a playlist name, or a fixed section for the folder. */
    val playlist: String,
    /** SAF document URI the bitmap can be decoded from. */
    val uri: String,
)
