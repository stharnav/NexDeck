package com.example.consolelauncher.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads games from a user-selected game directory using SAF.
 */
class GameDirectoryRepository(context: Context) {

    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val thumbnailCache = ConcurrentHashMap<String, ImageBitmap?>()
    private val gameDirectoryAccess = GameDirectoryAccess(context)

    companion object {
        private const val TAG = "GameDirectoryRepository"
    }

    /**
     * Loads all games from the game directory.
     */
    suspend fun loadGames(): List<GameDirectoryEntry> = withContext(Dispatchers.IO) {
        if (!gameDirectoryAccess.hasAccess()) {
            Log.d(TAG, "No SAF access granted to game directory")
            return@withContext emptyList()
        }

        val romUris = gameDirectoryAccess.listRomFiles()
        if (romUris.isEmpty()) {
            Log.d(TAG, "No ROM files found in game directory")
            return@withContext emptyList()
        }

        val games = mutableListOf<GameDirectoryEntry>()
        for ((index, romUri) in romUris.withIndex()) {
            val displayName = gameDirectoryAccess.getDisplayName(romUri) ?: "Unknown"
            val label = displayName
                .replace(".zip", "")
                .replace(".7z", "")
                .replace(".rar", "")
                .replace(".gba", "")
                .replace(".gb", "")
                .replace(".gbc", "")
                .replace(".nes", "")
                .replace(".sfc", "")
                .replace(".smc", "")
                .replace(".n64", "")
                .replace(".nds", "")
                .replace(".3ds", "")
                .replace(".gcm", "")
                .replace(".iso", "")
                .replace(".cso", "")
                .replace(".pbp", "")
                .replace(".chd", "")
                .replace(".cue", "")
                .replace(".bin", "")
                .replace(".md", "")
                .replace(".gen", "")
                .replace(".pce", "")
                .replace(".ws", "")
                .replace(".wsc", "")
                .replace(".ngp", "")
                .replace(".ngc", "")
                .replace(".a26", "")
                .replace(".a78", "")
                .replace(".col", "")
                .replace(".int", "")
                .replace(".sms", "")
                .replace(".gg", "")
                .replace(".vec", "")
                .replace(".rom", "")
                .replace(".z64", "")
                .replace(".v64", "")
                .replace(".fds", "")
                .replace(".wbfs", "")
                .replace(".sgx", "")

            val entry = buildGameEntry(
                id = "game_$index",
                label = label,
                romUri = romUri
            )
            games.add(entry)
        }

        Log.d(TAG, "Loaded ${games.size} games from game directory")
        games
    }

    /**
     * Builds a GameDirectoryEntry with launch intent and cached thumbnail.
     */
    private fun buildGameEntry(
        id: String,
        label: String,
        romUri: Uri
    ): GameDirectoryEntry {
        val launchIntent = buildLaunchIntent(romUri)
        val thumbnailBitmap = loadThumbnail(romUri)
        return GameDirectoryEntry(
            id = id,
            label = label,
            romUri = romUri,
            launchIntent = launchIntent,
            thumbnailBitmap = thumbnailBitmap
        )
    }

    /**
     * Builds the intent to launch a game using RetroArch.
     * Uses the same approach as RetroArchRepository.
     */
    private fun buildLaunchIntent(romUri: Uri): Intent {
        // Use RetroArch to launch the game
        // We need to detect which RetroArch is installed
        val retroArchPackage = detectRetroArchPackage() ?: "com.retroarch"
        
        val configFilePath = "/storage/emulated/0/Android/data/$retroArchPackage/files/retroarch.cfg"

        return Intent(Intent.ACTION_MAIN).apply {
            setComponent(android.content.ComponentName(retroArchPackage, "com.retroarch.browser.retroactivity.RetroActivityFuture"))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            addCategory(Intent.CATEGORY_LAUNCHER)
            // Pass ROM URI
            putExtra("ROM", romUri.toString())
            // Pass config file path
            putExtra("CONFIGFILE", configFilePath)
            // Let RetroArch auto-detect core
        }
    }

    /**
     * Detects which RetroArch variant is installed.
     */
    private fun detectRetroArchPackage(): String? {
        val packageManager = appContext.packageManager
        val variants = listOf(
            "com.retroarch",
            "com.retroarch.aarch64",
            "com.retroarch.ra32",
            "com.retroarch.ra64"
        )
        return variants.firstOrNull { pkg ->
            try {
                packageManager.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Loads and caches a thumbnail from a URI.
     */
    private fun loadThumbnail(uri: Uri): ImageBitmap? {
        val cacheKey = uri.toString()
        // Check cache first
        val cached = thumbnailCache[cacheKey]
        if (cached != null) return cached
        
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
            inputStream.close()
            val bitmap = drawable?.toBitmap()?.asImageBitmap()
            bitmap?.let { thumbnailCache[cacheKey] = it }
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load thumbnail: $uri", e)
            null
        }
    }

    /**
     * Checks if game directory access is available.
     */
    fun hasAccess(): Boolean = gameDirectoryAccess.hasAccess()

    /**
     * Gets the GameDirectoryAccess instance for UI integration.
     */
    fun getGameDirectoryAccess(): GameDirectoryAccess = gameDirectoryAccess
}