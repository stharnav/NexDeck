package com.example.consolelauncher.data

import android.content.Intent

/**
 * Represents a game that RetroArch knows about from one of its playlists and can launch directly.
 */
data class RetroGameEntry(
    val id: String,
    val label: String,
    /** Readable core name ("Gambatte", ...) or "Auto-detect". */
    val coreName: String,
    /** Name of the `.lpl` playlist the game came from, used as section title. */
    val playlistName: String,
    /** Path exactly as stored in the playlist: absolute path, `content://` URI or `saf://…`. */
    val romPath: String,
    /** Absolute path or content URI of the box art, loaded lazily by the card. */
    val thumbnailPath: String?,
    /** SAF document URI of the cover art in `thumbnails/<system>/Named_Boxarts`, if available. */
    val boxArtUri: String?,
    /** RetroArch variant that owns the game. */
    val launchPackage: String,
    val launchIntent: Intent,
) {
    /**
     * Unique key for grid focus management.
     */
    val gridKey: String = "retro_$id"
}
