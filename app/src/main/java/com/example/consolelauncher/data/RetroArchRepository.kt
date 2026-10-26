package com.example.consolelauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads the games RetroArch keeps in its `.lpl` playlists and builds the intents that launch them.
 *
 * Access to the playlists is granted through the Storage Access Framework, see
 * [RetroArchPlaylistAccess]. No storage permission is needed.
 */
class RetroArchRepository(context: Context) {

    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val thumbnailCache = ConcurrentHashMap<String, ImageBitmap>()
    private val missingThumbnails = ConcurrentHashMap.newKeySet<String>()
    private val playlistAccess = RetroArchPlaylistAccess(appContext)

    @Volatile
    private var cachedPackage: String? = null

    @Volatile
    private var packageResolved = false

    companion object {
        private const val TAG = "RetroArchRepository"
        private const val RETRO_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture"
        private const val CORE_SUFFIX_ANDROID = "_libretro_android.so"
        private const val CORE_SUFFIX = "_libretro.so"
        private const val DETECT = "DETECT"

        private val RETROARCH_VARIANTS = listOf(
            "com.retroarch",
            "com.retroarch.aarch64",
            "com.retroarch.ra32",
            "com.retroarch.ra64",
        )
    }

    /** Package name of the installed RetroArch variant, or null when it is not installed. */
    val packageName: String?
        get() = resolvePackage()

    private fun resolvePackage(): String? {
        if (packageResolved) return cachedPackage
        cachedPackage = RETROARCH_VARIANTS.firstOrNull { isPackageInstalled(it) }
        packageResolved = true
        return cachedPackage
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        appContext.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * The RetroArch variant that owns the data of [corePath]. Playlists of a
     * `/data/user/0/com.retroarch.aarch64/cores/...` style path therefore end up on the variant
     * that actually runs the game.
     */
    private fun packageFromCorePath(corePath: String): String? {
        val segment = corePath.split('/').firstOrNull { it in RETROARCH_VARIANTS }
        return segment?.takeIf { isPackageInstalled(it) }
    }

    /** Drops the cached installation state so the next call re-queries the package manager. */
    fun refresh() {
        packageResolved = false
        cachedPackage = null
    }

    /**
     * Loads all games from all `.lpl` playlists, grouped and sorted for display.
     * Returns an empty list if RetroArch is not installed or no playlist access was granted.
     */
    suspend fun loadRetroGames(): List<RetroGameEntry> = withContext(Dispatchers.IO) {
        if (resolvePackage() == null) {
            Log.d(TAG, "RetroArch not installed")
            return@withContext emptyList()
        }
        if (!playlistAccess.hasAccess()) {
            Log.d(TAG, "No SAF access granted to the RetroArch playlists folder")
            return@withContext emptyList()
        }

        val playlistUris = playlistAccess.listPlaylistFiles()
        if (playlistUris.isEmpty()) {
            Log.d(TAG, "No .lpl playlists found in the granted folder")
            return@withContext emptyList()
        }

        val allGames = mutableListOf<RetroGameEntry>()
        for (playlistUri in playlistUris) {
            val playlistName = getDisplayName(playlistUri)
                ?.removeSuffix(".lpl")
                ?.takeIf { it.isNotBlank() }
                ?: continue
            allGames.addAll(parsePlaylistFile(playlistUri, playlistName))
        }

        // The same game can be in several playlists (favourites, history, ...); keep the first.
        val seenPaths = mutableSetOf<String>()
        val uniqueGames = allGames.filter { seenPaths.add(it.romPath) }

        Log.d(TAG, "Loaded ${uniqueGames.size} unique games from ${playlistUris.size} playlists")
        uniqueGames.sortedWith(
            compareBy<RetroGameEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.playlistName }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )
    }

    /** Display name of a SAF document, used to name the playlist. */
    private fun getDisplayName(uri: Uri): String? = try {
        contentResolver.query(
            uri,
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to get display name for $uri", e)
        null
    }

    /** Parses a single `.lpl` playlist file (JSON format). */
    private suspend fun parsePlaylistFile(playlistUri: Uri, playlistName: String): List<RetroGameEntry> {
        val jsonText = playlistAccess.readPlaylistFile(playlistUri) ?: return emptyList()
        return try {
            val json = JSONObject(jsonText)
            val defaultCorePath = json.optString("default_core_path", "").trim()
            val defaultCoreName = json.optString("default_core_name", "").trim()
            val items = json.optJSONArray("items") ?: return emptyList()
            val games = mutableListOf<RetroGameEntry>()

            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val romPath = item.optString("path", "").trim().takeIf { it.isNotEmpty() } ?: continue
                val corePath = item.optString("core_path", "").trim()
                val coreName = item.optString("core_name", "").trim()

                // RetroArch itself prefers the playlist default core over the entry when the entry
                // is set to "DETECT".
                val effectiveCorePath = corePath.takeIf { it.isNotBlank() && it != DETECT }
                    ?: defaultCorePath.takeIf { it.isNotBlank() && it != DETECT }
                val effectiveCoreName = coreName.takeIf { it.isNotBlank() && it != DETECT }
                    ?: defaultCoreName.takeIf { it.isNotBlank() && it != DETECT }
                val resolvedCorePath = effectiveCorePath

                // Games from a playlist of one RetroArch variant may run on another (Aarch64 vs the
                // Play Store build), so ask the core path which package the game belongs to.
                val launchPackage = (resolvedCorePath?.let(::packageFromCorePath)
                    ?: resolvePackage()) ?: return emptyList()

                val launchIntent = buildLaunchIntent(romPath, resolvedCorePath, launchPackage) ?: continue
                val label = item.optString("label", "").trim()
                    .ifEmpty { romPath.substringAfterLast('/').substringBeforeLast('.') }
                val thumbnail = item.optString("thumbnail", "").trim().takeIf { it.isNotEmpty() }

                games.add(
                    RetroGameEntry(
                        id = "$playlistName#${romPath.hashCode()}",
                        label = label,
                        coreName = effectiveCoreName
                            ?: resolvedCorePath?.substringAfterLast('/')
                            ?.trimCoreSuffix()
                            ?: "Auto-detect",
                        playlistName = playlistName,
                        romPath = romPath,
                        thumbnailPath = thumbnail,
                        launchPackage = launchPackage,
                        launchIntent = launchIntent,
                    ),
                )
            }
            Log.d(TAG, "Parsed ${games.size} games from playlist: $playlistName")
            games
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse playlist: $playlistName", e)
            emptyList()
        }
    }

    private fun String.trimCoreSuffix(): String =
        removeSuffix(CORE_SUFFIX_ANDROID).removeSuffix(CORE_SUFFIX).removeSuffix(".so")

    /**
     * Builds the intent that makes RetroArch boot straight into a game.
     *
     * RetroArch's native activity reads the `ROM`, `LIBRETRO` and `CONFIGFILE` extras, exactly
     * like the command line form the launcher of the device uses. The core is only passed when a
     * real core path is known; RetroArch then auto-detects from the ROM otherwise.
     */
    private fun buildLaunchIntent(romPath: String, corePath: String?, launchPackage: String): Intent? {
        val rom = romPath.trim()
        if (rom.isEmpty()) return null

        return Intent(Intent.ACTION_MAIN).apply {
            setComponent(ComponentName(launchPackage, RETRO_ACTIVITY))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (rom.startsWith("content://")) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            putExtra("ROM", rom)
            if (corePath != null) {
                putExtra("LIBRETRO", corePath)
            }
            putExtra("CONFIGFILE", "/storage/emulated/0/Android/data/$launchPackage/files/retroarch.cfg")
        }.also { intent ->
            Log.d(TAG, "Built RetroArch launch intent for ${launchPackage}: ROM=$rom, LIBRETRO=$corePath")
        }
    }

    /**
     * Makes sure RetroArch may read a `content://` ROM that lives in a folder this launcher has
     * access to. `saf://` paths are already authorised for RetroArch and must be passed through
     * untouched, so they are left alone here.
     */
    fun grantRomAccess(romPath: String, launchPackage: String) {
        val rom = romPath.trim()
        if (!rom.startsWith("content://")) return
        try {
            appContext.grantUriPermission(launchPackage, Uri.parse(rom), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to grant RetroArch access to $rom", e)
        }
    }

    /**
     * Loads box art for a playlist entry. Failures are remembered so a missing thumbnail is only
     * looked up once.
     */
    suspend fun loadThumbnail(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        thumbnailCache[path]?.let { return@withContext it }
        if (path in missingThumbnails) return@withContext null

        val bitmap = decodeThumbnail(path)
        if (bitmap != null) {
            thumbnailCache[path] = bitmap
        } else {
            missingThumbnails.add(path)
        }
        bitmap
    }

    private fun decodeThumbnail(path: String): ImageBitmap? = try {
        val stream = when {
            path.startsWith("content://") -> contentResolver.openInputStream(Uri.parse(path))
            path.startsWith("file://") -> contentResolver.openInputStream(Uri.parse(path))
            else -> playlistAccess.openUnderTree(path)
        }
        stream?.use { input ->
            android.graphics.drawable.Drawable.createFromStream(input, null)
                ?.toBitmap()
                ?.asImageBitmap()
        }
    } catch (e: Exception) {
        Log.d(TAG, "Failed to load thumbnail: $path", e)
        null
    }

    /** Checks if RetroArch is available. */
    fun isAvailable(): Boolean = resolvePackage() != null

    /** Checks if SAF access to the playlists is granted. */
    fun hasPlaylistAccess(): Boolean = playlistAccess.hasAccess()

    /** Gets the [RetroArchPlaylistAccess] instance for the permission flow. */
    fun getPlaylistAccess(): RetroArchPlaylistAccess = playlistAccess
}
