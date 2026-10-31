package com.example.consolelauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

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
    /** Decoded ambient backgrounds, small LRU so hovering through a playlist cannot OOM. */
    private val snapCache = LruCache<String, ImageBitmap>(3)
    private val missingSnaps = ConcurrentHashMap.newKeySet<String>()
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

        /** Playlist name of RetroArch's built-in content history playlist. */
        private const val HISTORY_PLAYLIST = "content_history"

        /** System folder names of the history playlist itself, usually absent from thumbnails. */
        private val HISTORY_SYSTEM_FOLDERS = listOf(HISTORY_PLAYLIST, "history")

        /**
         * Artwork a history entry shows, best first: the title screen of its system, falling back
         * to its box art.
         */
        private val HISTORY_ARTWORK_FOLDERS = listOf(
            RetroArchPlaylistAccess.TITLES_DIR,
            RetroArchPlaylistAccess.BOXARTS_DIR,
        )

        /** Games shown in the home screen history section. */
        private const val HISTORY_LIMIT = 10

        /**
         * Width in dp of the largest artwork slot (the history tile). Decoded images are
         * rescaled to this size in real pixels once, so the GPU draws them ~1:1.
         */
        private const val ART_TARGET_DP = 200
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
    suspend fun loadRetroGames(resolveSnaps: Boolean = false): List<RetroGameEntry> = withContext(Dispatchers.IO) {
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
            allGames.addAll(parsePlaylistFile(playlistUri, playlistName, resolveSnaps = resolveSnaps))
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

    /**
     * Loads RetroArch's content history (`content_history.lpl`): the last played games in the
     * order RetroArch recorded them, limited to [HISTORY_LIMIT] entries. Entries are shown with
     * the title screen artwork of their system when available, falling back to the box art.
     * Returns an empty list if RetroArch is not installed, no playlist access was granted or
     * nothing was played yet.
     */
    suspend fun loadHistory(resolveSnaps: Boolean = false): List<RetroGameEntry> = withContext(Dispatchers.IO) {
        if (resolvePackage() == null) {
            Log.d(TAG, "RetroArch not installed")
            return@withContext emptyList()
        }
        if (!playlistAccess.hasAccess()) {
            Log.d(TAG, "No SAF access granted to the RetroArch playlists folder")
            return@withContext emptyList()
        }

        val historyUri = playlistAccess.findHistoryPlaylist()
        if (historyUri == null) {
            Log.d(TAG, "No content history playlist found in the granted folder")
            return@withContext emptyList()
        }

        val history = parsePlaylistFile(
            playlistUri = historyUri,
            playlistName = HISTORY_PLAYLIST,
            systemFolders = HISTORY_SYSTEM_FOLDERS,
            artworkFolders = HISTORY_ARTWORK_FOLDERS,
            scanEverySystemFolder = true,
            resolveSnaps = resolveSnaps,
        )
        // The history can hold the same ROM path twice (a game launched from within RetroArch is
        // recorded again when it is launched through the launcher). RetroArch keeps the newest run
        // first, so the first occurrence is kept; duplicate ids would crash the grid keyed by them.
        val seenPaths = mutableSetOf<String>()
        val uniqueHistory = history.filter {
            seenPaths.add(it.romPath)
        }
        Log.d(TAG, "Loaded ${history.size} history entries (${uniqueHistory.size} unique)")
        uniqueHistory.take(HISTORY_LIMIT)
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

    /**
     * Parses a single `.lpl` playlist file (JSON format). With [scanEverySystemFolder] the
     * artwork scan also covers system folders under `thumbnails/` that no entry references, so
     * entries without their own system (RetroArch history of content launched without a playlist)
     * can still find their cover art by name.
     */
    private suspend fun parsePlaylistFile(
        playlistUri: Uri,
        playlistName: String,
        systemFolders: List<String> = listOf(playlistName),
        artworkFolders: List<String> = listOf(RetroArchPlaylistAccess.BOXARTS_DIR),
        scanEverySystemFolder: Boolean = false,
        resolveSnaps: Boolean = false,
    ): List<RetroGameEntry> {
        val jsonText = playlistAccess.readPlaylistFile(playlistUri) ?: return emptyList()
        return try {
            val json = JSONObject(jsonText)
            val defaultCorePath = json.optString("default_core_path", "").trim()
            val defaultCoreName = json.optString("default_core_name", "").trim()
            val items = json.optJSONArray("items") ?: return emptyList()

            // Collect the raw entries first: cross-system playlists (history, favourites) have no
            // thumbnail folder of their own. Every entry names the system database it belongs to
            // (`db_name`), which is the folder its cover art lives in, so all systems have to be
            // known before the art can be scanned.
            val rawItems = mutableListOf<PlaylistItem>()
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
                val label = item.optString("label", "").trim()
                    .ifEmpty { romPath.substringAfterLast('/').substringBeforeLast('.') }
                val dbName = item.optString("db_name", "").trim()
                    .removeSuffix(".lpl")
                    .takeIf { it.isNotBlank() }
                val thumbnailPath = item.optString("thumbnail", "").trim().takeIf { it.isNotEmpty() }

                rawItems.add(
                    PlaylistItem(romPath, label, effectiveCorePath, effectiveCoreName, thumbnailPath, dbName),
                )
            }

            // Cover art lives in thumbnails/<system>/<artwork folder>, named after the ROM or the
            // playlist label. Scan every involved system folder once per artwork folder; an entry
            // prefers the folder of its own system over the playlist's folders, and the artwork
            // folders are tried best first.
            val systems = rawItems.mapNotNull { it.dbName }.distinct()
            val artBySystem = artworkFolders.associateWith { artworkFolder ->
                playlistAccess.listArtworkPerSystem(
                    systemFolders + systems,
                    artworkFolder,
                    scanEverySystemFolder = scanEverySystemFolder,
                )
            }
            // In-game screenshots (`Named_Snaps`) only matter while ambient mode is on; skipping
            // the extra SAF scan otherwise keeps the playlist load fast.
            val snapsBySystem = if (resolveSnaps) {
                playlistAccess.listArtworkPerSystem(
                    systemFolders + systems,
                    RetroArchPlaylistAccess.SNAPS_DIR,
                    scanEverySystemFolder = scanEverySystemFolder,
                )
            } else {
                emptyMap()
            }
            val games = mutableListOf<RetroGameEntry>()

            for (raw in rawItems) {
                // Games from a playlist of one RetroArch variant may run on another (Aarch64 vs the
                // Play Store build), so ask the core path which package the game belongs to.
                val launchPackage = (raw.corePath?.let(::packageFromCorePath)
                    ?: resolvePackage()) ?: return emptyList()

                val launchIntent = buildLaunchIntent(raw.romPath, raw.corePath, launchPackage) ?: continue
                val boxArtUri = boxArtFor(artBySystem, raw, systemFolders, artworkFolders)
                val snapUri = if (resolveSnaps) {
                    boxArtFor(
                        mapOf(RetroArchPlaylistAccess.SNAPS_DIR to snapsBySystem),
                        raw,
                        systemFolders,
                        listOf(RetroArchPlaylistAccess.SNAPS_DIR),
                    )
                } else {
                    null
                }

                games.add(
                    RetroGameEntry(
                        id = "$playlistName#${raw.romPath.hashCode()}",
                        label = raw.label,
                        coreName = raw.coreName
                            ?: raw.corePath?.substringAfterLast('/')
                            ?.trimCoreSuffix()
                            ?: "Auto-detect",
                        playlistName = playlistName,
                        romPath = raw.romPath,
                        thumbnailPath = raw.thumbnailPath,
                        boxArtUri = boxArtUri?.toString(),
                        snapUri = snapUri?.toString(),
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

    /** A playlist entry before the launch intent and its cover art are resolved. */
    private data class PlaylistItem(
        val romPath: String,
        val label: String,
        val corePath: String?,
        val coreName: String?,
        val thumbnailPath: String?,
        /** System database the entry belongs to (`thumbnails/<db_name>` holds its cover art). */
        val dbName: String?,
    )

    /**
     * Resolves the cover art of one playlist entry. The entry's own system (`db_name`) is
     * preferred, so a cross-system playlist such as the history picks the art of the system the
     * game runs on; the playlist's own folders are the fallback. Within a system, the artwork
     * folders of [artworkFolders] are tried best first. Art files are named after the ROM or the
     * playlist label.
     *
     * A final pass searches every other scanned system folder, for history entries whose content
     * was launched without a playlist and therefore stores no `db_name`.
     */
    private fun boxArtFor(
        artBySystem: Map<String, Map<String, Map<String, Uri>>>,
        item: PlaylistItem,
        fallbackFolders: List<String>,
        artworkFolders: List<String>,
    ): Uri? {
        val keys = setOf(romBaseName(item.romPath).normalizedKey(), item.label.normalizedKey())
        val folders = buildList {
            item.dbName?.let { add(it.normalizedKey()) }
            fallbackFolders.forEach { add(it.normalizedKey()) }
        }.distinct()
        return artworkFolders.firstNotNullOfOrNull { artworkFolder ->
            artBySystem[artworkFolder]?.let { artByFolder ->
                folders.firstNotNullOfOrNull { folder ->
                    artByFolder[folder]?.let { art -> keys.firstNotNullOfOrNull(art::get) }
                }
            }
        }
            ?: artworkFolders.firstNotNullOfOrNull { artworkFolder ->
                artBySystem[artworkFolder]
                    ?.filterKeys { it !in folders }
                    ?.firstNotNullOfOrNull { (folder, art) ->
                        keys.firstNotNullOfOrNull(art::get)?.also { uri ->
                            Log.d(
                                TAG,
                                "Box art for '${item.label.ifEmpty { item.romPath }}' found via extra system folder '$folder': $uri",
                            )
                        }
                    }
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
     * Loads the artwork stored in the playlist for a game (`thumbnail` entry). It is either an
     * absolute path or a URI. Failures are remembered so a game without artwork is only looked
     * up once.
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

    /**
     * Loads box art by its SAF document URI (from [RetroArchPlaylistAccess.listArtworkPerSystem]).
     * Failures are remembered so a missing cover is only looked up once.
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

    private fun decodeBoxArt(uriString: String): ImageBitmap? =
        decodeArtwork { contentResolver.openInputStream(Uri.parse(uriString)) }

    /**
     * Loads the ambient background of a game, the in-game screenshot that RetroArch keeps in
     * `thumbnails/<system>/Named_Snaps`. Decoded only as large as the screen's longest edge, and
     * kept in a tiny LRU so drifting through a long playlist does not grow the heap.
     */
    suspend fun loadSnap(uriString: String): ImageBitmap? = withContext(Dispatchers.IO) {
        snapCache.get(uriString)?.let { return@withContext it }
        if (uriString in missingSnaps) return@withContext null

        val bitmap = decodeSnap(uriString)
        if (bitmap != null) {
            snapCache.put(uriString, bitmap)
        } else {
            missingSnaps.add(uriString)
        }
        bitmap
    }

    private fun decodeSnap(uriString: String): ImageBitmap? {
        val source = try {
            contentResolver.openInputStream(Uri.parse(uriString))?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to decode ambient snap $uriString", e)
            null
        } ?: return null
        if (source.width <= 0 || source.height <= 0) return source.asImageBitmap()

        val metrics = appContext.resources.displayMetrics
        val longestPx = maxOf(metrics.widthPixels, metrics.heightPixels)
        val longestEdge = maxOf(source.width, source.height)
        if (longestEdge <= longestPx) return source.asImageBitmap()

        val scale = longestPx.toFloat() / longestEdge
        val prepared = Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        source.recycle()
        return prepared.asImageBitmap()
    }

    private fun decodeThumbnail(path: String): ImageBitmap? = when {
        path.startsWith("content://") || path.startsWith("file://") ->
            decodeArtwork { contentResolver.openInputStream(Uri.parse(path)) }

        else -> decodeArtwork { playlistAccess.openUnderTree(path) }
    }

    /**
     * Decodes an artwork image and prepares it for the tiles it is shown in. The source is
     * rescaled to the slot size in real pixels once, here, instead of by the GPU on every frame,
     * and upscaled sources (low resolution title screens) get a light sharpen, because plain
     * bilinear upscaling looks mushy on high density screens.
     */
    private fun decodeArtwork(openStream: () -> InputStream?): ImageBitmap? {
        val source = try {
            openStream()?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to decode artwork", e)
            null
        } ?: return null

        if (source.width <= 0 || source.height <= 0) return source.asImageBitmap()

        val targetWidth = (ART_TARGET_DP * appContext.resources.displayMetrics.density).roundToInt()
        val scale = targetWidth.toFloat() / source.width
        val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        val prepared = when {
            scale in 0.9f..1.1f -> source

            scale > 1f -> {
                // Upscale with a full size bilinear pass, then sharpen: the gallery style
                // treatment that keeps low resolution title screens readable.
                sharpen(Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true))
            }

            else -> Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        }
        if (prepared !== source) source.recycle()
        return prepared.asImageBitmap()
    }

    /**
     * Mixes a light 3x3 unsharp kernel over [source]: 40% of the 5-point sharpen amount, so
     * upscaled pixel art gains back edge definition without halos or ringing.
     */
    private fun sharpen(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = IntArray(pixels.size)

        for (y in 0 until height) {
            val row = y * width
            val rowUp = if (y == 0) row else row - width
            val rowDown = if (y == height - 1) row else row + width
            for (x in 0 until width) {
                val left = if (x == 0) x else x - 1
                val right = if (x == width - 1) x else x + 1

                val p = pixels[row + x]
                val alpha = p ushr 24
                val up = pixels[rowUp + x]
                val down = pixels[rowDown + x]
                val leftPixel = pixels[row + left]
                val rightPixel = pixels[row + right]

                val r = sharpenChannel((p shr 16) and 0xFF, (up shr 16) and 0xFF, (down shr 16) and 0xFF, (leftPixel shr 16) and 0xFF, (rightPixel shr 16) and 0xFF)
                val g = sharpenChannel((p shr 8) and 0xFF, (up shr 8) and 0xFF, (down shr 8) and 0xFF, (leftPixel shr 8) and 0xFF, (rightPixel shr 8) and 0xFF)
                val b = sharpenChannel(p and 0xFF, up and 0xFF, down and 0xFF, leftPixel and 0xFF, rightPixel and 0xFF)

                out[row + x] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    /** `center + 40% of (4 * center - neighbours)`, clamped; half the strength of the kernel. */
    private fun sharpenChannel(center: Int, up: Int, down: Int, left: Int, right: Int): Int =
        (center + ((4 * center - up - down - left - right) * 2) / 5).coerceIn(0, 255)

    /** Checks if RetroArch is available. */
    fun isAvailable(): Boolean = resolvePackage() != null

    /** Checks if SAF access to the playlists is granted. */
    fun hasPlaylistAccess(): Boolean = playlistAccess.hasAccess()

    /** Checks if the granted folder also contains the artwork folder. */
    fun hasThumbnailAccess(): Boolean = playlistAccess.hasThumbnailAccess()

    /** Gets the [RetroArchPlaylistAccess] instance for the permission flow. */
    fun getPlaylistAccess(): RetroArchPlaylistAccess = playlistAccess
}
