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
    private val boxArtCache = ConcurrentHashMap<String, ImageBitmap>()
    private val missingBoxArt = ConcurrentHashMap.newKeySet<String>()
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

        /** Sub folders RetroArch uses for the artwork of a playlist, in order of preference. */
        private val THUMBNAIL_SUBDIRS = listOf("Named_Boxarts", "Named_Snaps", "Named_Titles")

        /** Characters RetroArch scrubs from a label before using it as a file name. */
        private val THUMBNAIL_UNSAFE_CHARS = "&*/:`\"<>?\\|".toCharArray()

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
        // Cover art of this system, keyed by file base name without extension.
        val boxArtByBaseName = playlistAccess.listBoxArt(playlistName)
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
                val storedThumbnail = item.optString("thumbnail", "").trim().takeIf { it.isNotEmpty() }

                // Cover art lives in thumbnails/<system>/Named_Boxarts, named after the ROM or
                // the playlist label. Look it up in the scanned map instead of guessing paths.
                val boxArtUri = boxArtByBaseName[romBaseName(romPath).normalizedKey()]
                    ?: boxArtByBaseName[label.normalizedKey()]

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
<<<<<<< HEAD
                        thumbnailPaths = buildThumbnailCandidates(playlistName, label, storedThumbnail),
=======
                        thumbnailPath = thumbnail,
                        boxArtUri = boxArtUri?.toString(),
>>>>>>> 9e1d047 (fixed card icon bugs)
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

    /** Base name of a ROM path (`game.gba`, `content://.../game%20I.zip` -> `game`, `game I`). */
    private fun romBaseName(romPath: String): String {
        val raw = if (romPath.startsWith("content://")) {
            Uri.parse(romPath).lastPathSegment?.let { Uri.decode(it) } ?: romPath
        } else {
            romPath
        }
        return raw.substringAfterLast('/').substringBeforeLast('.')
    }

    /**
     * Builds the places where RetroArch may keep the artwork of a game.
     *
     * Playlists normally do not contain a `thumbnail` entry, so the standard layout is used:
     * `<RetroArch>/thumbnails/<playlist>/Named_Boxarts/<label>.png`, then snaps and titles. The
     * short name (the label up to the first bracket) is tried as well, because some thumbnail
     * packs are named that way.
     */
    private fun buildThumbnailCandidates(
        playlistName: String,
        label: String,
        storedThumbnail: String?,
    ): List<String> = buildList {
        val thumbnailsDir = playlistAccess.thumbnailsDirectory()

        if (!storedThumbnail.isNullOrEmpty()) {
            add(
                when {
                    storedThumbnail.startsWith("content://") ||
                        storedThumbnail.startsWith("file://") ||
                        storedThumbnail.startsWith("/") -> storedThumbnail
                    thumbnailsDir != null -> "$thumbnailsDir/$storedThumbnail"
                    else -> storedThumbnail
                },
            )
        }

        if (thumbnailsDir == null) return@buildList

        val names = linkedSetOf(label.toThumbnailFileName())
        label.substringBefore(" (").takeIf { it.isNotBlank() && it != label }
            ?.let { names.add(it.toThumbnailFileName()) }

        for (subdir in THUMBNAIL_SUBDIRS) {
            for (name in names) {
                add("$thumbnailsDir/$playlistName/$subdir/$name")
            }
        }
    }

    /** File name RetroArch uses for the artwork of [this] label. */
    private fun String.toThumbnailFileName(): String {
        val scrubbed = buildString(length) {
            for (char in this@toThumbnailFileName) {
                append(if (char in THUMBNAIL_UNSAFE_CHARS) '_' else char)
            }
        }
        return "$scrubbed.png"
    }

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
     * Loads artwork for a playlist entry, trying the candidates in order. Failures are remembered
     * so a game without artwork is only looked up once.
     */
    suspend fun loadThumbnail(paths: List<String>): ImageBitmap? = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext null
        val cacheKey = paths.joinToString("\n")
        thumbnailCache[cacheKey]?.let { return@withContext it }
        if (cacheKey in missingThumbnails) return@withContext null

        var bitmap: ImageBitmap? = null
        for (path in paths) {
            bitmap = decodeThumbnail(path)
            if (bitmap != null) break
        }

        if (bitmap != null) {
            thumbnailCache[cacheKey] = bitmap
        } else {
            missingThumbnails.add(cacheKey)
        }
        bitmap
    }

    /**
     * Loads box art by its SAF document URI (from [RetroArchPlaylistAccess.listBoxArt]). Failures
     * are remembered so a missing cover is only looked up once.
     */
    suspend fun loadBoxArt(uriString: String): ImageBitmap? = withContext(Dispatchers.IO) {
        boxArtCache[uriString]?.let { return@withContext it }
        if (uriString in missingBoxArt) return@withContext null

        val bitmap = decodeBoxArt(uriString)
        if (bitmap != null) {
            boxArtCache[uriString] = bitmap
        } else {
            missingBoxArt.add(uriString)
        }
        bitmap
    }

    private fun decodeBoxArt(uriString: String): ImageBitmap? = try {
        contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
            android.graphics.drawable.Drawable.createFromStream(input, null)
                ?.toBitmap()
                ?.asImageBitmap()
        }
    } catch (e: Exception) {
        Log.d(TAG, "Failed to load box art: $uriString", e)
        null
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

    /** Checks if the granted folder also contains the artwork folder. */
    fun hasThumbnailAccess(): Boolean = playlistAccess.hasThumbnailAccess()

    /** Gets the [RetroArchPlaylistAccess] instance for the permission flow. */
    fun getPlaylistAccess(): RetroArchPlaylistAccess = playlistAccess
}
