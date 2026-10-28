package com.example.consolelauncher.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Manages access to RetroArch playlists via Storage Access Framework (SAF).
 *
 * RetroArch ships a [DocumentsProvider][android.provider.DocumentsProvider] with the authority
 * `<package>.documents` that exposes its data directory. The user grants a tree on one of the
 * folders in there (usually `files/RetroArch`, which contains both `playlists` and `thumbnails`),
 * which lets us read the `.lpl` files and the matching box art.
 */
class RetroArchPlaylistAccess(context: Context) {

    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "RetroArchPlaylistAccess"
        private const val PREFS_NAME = "retroarch_playlist_access"
        private const val KEY_PLAYLISTS_URI = "playlists_tree_uri"
        private const val RETROARCH_DOCUMENTS_AUTHORITY = "com.retroarch.documents"

        /** Playlists normally live directly in the picked folder, but allow a little nesting. */
        private const val MAX_PLAYLIST_DEPTH = 3

        /** RetroArch data folders that never contain playlists, skipped to keep the scan fast. */
        private val SKIPPED_FOLDERS = setOf(
            "thumbnails", "saves", "states", "screenshots", "cheats",
            "downloads", "system", "temp", "logs", "config",
        )

        private const val PRIMARY_STORAGE = "/storage/emulated/0/"
        private const val LEGACY_PRIMARY_STORAGE = "/sdcard/"

        /** RetroArch folders that hold the cover art / title screen of a system. */
        internal const val BOXARTS_DIR = "Named_Boxarts"
        internal const val TITLES_DIR = "Named_Titles"

        /** The built-in playlist that records the last played games, newest first. */
        private const val HISTORY_PLAYLIST_FILE = "content_history.lpl"

        /** File names under which RetroArch stores its content history playlist. */
        private val HISTORY_PLAYLIST_FILES = setOf(HISTORY_PLAYLIST_FILE, "history.lpl")
    }

    /**
     * Checks if we have persisted access to the playlists directory.
     */
    fun hasAccess(): Boolean = getPlaylistsUri() != null

    /**
     * Gets the persisted playlists directory URI.
     */
    fun getPlaylistsUri(): Uri? {
        val uriString = prefs.getString(KEY_PLAYLISTS_URI, null)
        return uriString?.let { Uri.parse(it) }
    }

    /**
     * Launches the SAF directory picker to let user grant access to RetroArch playlists.
     * The caller should pass an ActivityResultLauncher<Intent> for the result.
     *
     * @param startInParent open the picker one folder above the current grant, which is the
     * RetroArch folder itself (it holds both `playlists` and `thumbnails`).
     */
    fun requestAccess(launcher: ActivityResultLauncher<Intent>, startInParent: Boolean = false) {
        Log.d(TAG, "Requesting SAF access to RetroArch playlists (parent=$startInParent)")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            val initialUri = if (startInParent) parentDocumentUri() else null
            if (initialUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            } else {
                // Pre-select RetroArch's documents provider so the picker opens on its data directory.
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://$RETROARCH_DOCUMENTS_AUTHORITY/"),
                )
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        launcher.launch(intent)
    }

    /** Document URI of the folder above the currently granted one, used to pre-navigate the picker. */
    private fun parentDocumentUri(): Uri? {
        val treeUri = getPlaylistsUri() ?: return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        val parentId = documentId.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
        val authority = treeUri.authority ?: return null
        if (parentId.isEmpty()) return null
        return DocumentsContract.buildDocumentUri(authority, parentId)
    }

    /**
     * Handles the result from the directory picker.
     * Call this from the ActivityResultLauncher callback.
     */
    fun handleResult(resultCode: Int, data: Intent?): Boolean {
        Log.d(TAG, "Handling SAF result: resultCode=$resultCode")
        if (resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { uri ->
                Log.d(TAG, "SAF result URI: $uri")
                // Persist the URI permission
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    val previous = getPlaylistsUri()
                    if (previous != null && previous != uri) {
                        // The new grant replaces the old one; do not keep the wider access around.
                        runCatching {
                            contentResolver.releasePersistableUriPermission(
                                previous,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                    }
                    prefs.edit().putString(KEY_PLAYLISTS_URI, uri.toString()).apply()
                    Log.d(TAG, "Persisted access to playlists directory: $uri")
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist URI permission", e)
                }
            }
        } else {
            Log.d(TAG, "SAF result not OK: $resultCode")
        }
        return false
    }

    /**
     * Releases the persisted access.
     */
    fun releaseAccess() {
        val uri = getPlaylistsUri()
        uri?.let {
            try {
                contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release URI permission", e)
            }
        }
        prefs.edit().remove(KEY_PLAYLISTS_URI).apply()
    }

    /**
     * Lists all `.lpl` files below the granted tree.
     *
     * The selection argument is applied in memory: not every documents provider supports SQL
     * selections, and the external storage provider does not filter on display names.
     */
    suspend fun listPlaylistFiles(): List<Uri> = scanPlaylists().playlists

    /**
     * Document URI of RetroArch's built-in content history playlist (`content_history.lpl`),
     * which lists the games that were played last, newest first. Null when the granted tree does
     * not contain it.
     */
    suspend fun findHistoryPlaylist(): Uri? = scanPlaylists().history

    private suspend fun scanPlaylists(): PlaylistScan = withContext(Dispatchers.IO) {
        val empty = PlaylistScan(emptyList(), null)
        val treeUri = getPlaylistsUri() ?: return@withContext empty
        val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext empty

        val playlists = mutableListOf<Uri>()
        val histories = mutableListOf<Pair<String, Uri>>()
        collectPlaylists(treeUri, rootDocumentId, 0, playlists, histories)
        Log.d(TAG, "Found ${playlists.size} playlist files")
        PlaylistScan(
            playlists = playlists,
            history = histories.firstOrNull { it.first == HISTORY_PLAYLIST_FILE }?.second
                ?: histories.firstOrNull()?.second,
        )
    }

    private fun collectPlaylists(
        treeUri: Uri,
        documentId: String,
        depth: Int,
        result: MutableList<Uri>,
        histories: MutableList<Pair<String, Uri>>,
    ) {
        if (depth > MAX_PLAYLIST_DEPTH) return

        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid document id: $documentId", e)
            return
        }

        try {
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idIndex < 0 || nameIndex < 0) return

                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: continue
                    val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null

                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> {
                            if (name.lowercase() !in SKIPPED_FOLDERS) {
                                collectPlaylists(treeUri, id, depth + 1, result, histories)
                            }
                        }

                        name.lowercase() in HISTORY_PLAYLIST_FILES -> {
                            // The built-in history playlists are kept for the home screen history
                            // and excluded from the regular system playlists.
                            histories.add(
                                name.lowercase() to DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                            )
                            Log.d(TAG, "Found history playlist: $name")
                        }

                        name.endsWith(".lpl", ignoreCase = true) &&
                            !name.startsWith("content_", ignoreCase = true) -> {
                            result.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                            Log.d(TAG, "Found playlist: $name")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list children of $documentId", e)
        }
    }

    /**
     * Reads a playlist file content from the DocumentsProvider.
     */
    suspend fun readPlaylistFile(uri: Uri): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read playlist file: $uri", e)
            null
        }
    }

    /**
     * Absolute path of the RetroArch `thumbnails` folder that belongs to the granted playlists
     * folder, if it can be derived.
     *
     * RetroArch keeps both folders side by side (`RetroArch/playlists` and
     * `RetroArch/thumbnails`), but playlists do not store the artwork paths, so the folder has to
     * be worked out from the granted tree.
     */
    fun thumbnailsDirectory(): String? {
        val absolute = treeAbsolutePath() ?: return null
        return when {
            absolute.endsWith("/thumbnails", ignoreCase = true) -> absolute
            absolute.endsWith("/playlists", ignoreCase = true) ->
                absolute.substringBeforeLast('/') + "/thumbnails"
            else -> "$absolute/thumbnails"
        }
    }

    /**
     * Lists the cover art of the `thumbnails/<system>/<artworkFolder>` folders of [systemFolders].
     * Every folder gets its own map from file base name (case/spacing-insensitive) to its document
     * URI, keyed by the folder's [normalizedKey], so a caller can pick the folder of a single
     * entry's system without mixing up games that share a name across systems. Folders that do not
     * exist below the granted tree (e.g. only `playlists` was picked) are simply absent.
     */
    suspend fun listArtworkPerSystem(
        systemFolders: List<String>,
        artworkFolder: String = BOXARTS_DIR,
    ): Map<String, Map<String, Uri>> = withContext(Dispatchers.IO) {
        val treeUri = getPlaylistsUri() ?: return@withContext emptyMap()
        val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext emptyMap()

        val thumbnailsId = childDirectoryId(treeUri, rootDocumentId, "thumbnails")
        if (thumbnailsId == null) {
            Log.d(TAG, "No thumbnails folder below the granted tree root: $rootDocumentId")
            return@withContext emptyMap()
        }

        val artBySystem = mutableMapOf<String, Map<String, Uri>>()
        for (systemFolder in systemFolders) {
            val systemKey = systemFolder.normalizedKey()
            if (systemKey.isEmpty() || systemKey in artBySystem) continue

            val systemId = childDirectoryId(treeUri, thumbnailsId, systemFolder)
                ?: childDirectoryIdNormalized(treeUri, thumbnailsId, systemFolder)
            if (systemId == null) {
                Log.d(TAG, "No '$systemFolder' folder below the thumbnails folder")
                continue
            }

            val artId = childDirectoryId(treeUri, systemId, artworkFolder)
            if (artId == null) {
                Log.d(TAG, "No $artworkFolder folder below the '$systemFolder' thumbnails")
                continue
            }

            val artByBaseName = mutableMapOf<String, Uri>()
            listDocuments(treeUri, artId) { id, displayName, isDirectory ->
                if (!isDirectory) {
                    artByBaseName[displayName.substringBeforeLast('.').normalizedKey()] =
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                }
                null
            }
            artBySystem[systemKey] = artByBaseName
            Log.d(TAG, "Found ${artByBaseName.size} $artworkFolder files for '$systemFolder'")
        }
        artBySystem
    }

    /** Document id of the child directory named [name] (case-insensitive), or null. */
    private fun childDirectoryId(treeUri: Uri, parentDocumentId: String, name: String): String? =
        listDocuments(treeUri, parentDocumentId) { id, displayName, isDirectory ->
            if (isDirectory && displayName.equals(name, ignoreCase = true)) id else null
        }

    /** Like [childDirectoryId], but compares names without spaces, dashes and case. */
    private fun childDirectoryIdNormalized(
        treeUri: Uri,
        parentDocumentId: String,
        name: String,
    ): String? {
        val wanted = name.normalizedKey()
        if (wanted.isEmpty()) return null
        return listDocuments(treeUri, parentDocumentId) { id, displayName, isDirectory ->
            if (isDirectory && displayName.normalizedKey() == wanted) id else null
        }
    }

    /** Runs [block] over every child of [parentDocumentId]; returns the first non-null result. */
    private inline fun <T> listDocuments(
        treeUri: Uri,
        parentDocumentId: String,
        block: (id: String, displayName: String, isDirectory: Boolean) -> T?,
    ): T? {
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid document id: $parentDocumentId", e)
            return null
        }
        return try {
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idIndex < 0 || nameIndex < 0) return null
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: continue
                    val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null
                    val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    block(id, name, isDirectory)?.let { return@use it }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list children of $parentDocumentId", e)
            null
        }
    }

    /**
     * Checks if the granted tree also gives access to the artwork. The user may have granted only
     * the `playlists` folder, in which case the thumbnails next to it cannot be read.
     */
    fun hasThumbnailAccess(): Boolean {
        val tree = treeAbsolutePath() ?: return false
        val thumbnails = thumbnailsDirectory() ?: return false
        return thumbnails == tree || thumbnails.startsWith("$tree/")
    }

    /** Absolute path of the granted tree, if the provider uses a file system style document id. */
    private fun treeAbsolutePath(): String? {
        val treeUri = getPlaylistsUri() ?: return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        return when {
            documentId.startsWith("primary:") ->
                PRIMARY_STORAGE + documentId.removePrefix("primary:")
            documentId.startsWith(LEGACY_PRIMARY_STORAGE) -> documentId
            documentId.startsWith("/") -> documentId
            else -> null
        }?.trimEnd('/')
    }

    /**
     * Opens a file below the granted tree by its absolute path.
     *
     * RetroArch stores the absolute path of box art in its playlists, while the granted tree is a
     * document URI. RetroArch's provider uses the absolute path as the document id, and the
     * external storage provider uses `primary:<path>`; both are tried here.
     */
    fun openUnderTree(absolutePath: String): InputStream? {
        val treeUri = getPlaylistsUri() ?: return null
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null

        for (documentId in documentIdCandidates(absolutePath, treeDocumentId)) {
            try {
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                contentResolver.openInputStream(documentUri)?.let { return it }
            } catch (e: Exception) {
                Log.d(TAG, "Cannot open $absolutePath as $documentId", e)
            }
        }
        return null
    }

    private fun documentIdCandidates(absolutePath: String, treeDocumentId: String): List<String> = buildList {
        if (treeDocumentId.startsWith("/")) add(absolutePath)
        if (absolutePath.startsWith(PRIMARY_STORAGE)) {
            add("primary:" + absolutePath.removePrefix(PRIMARY_STORAGE))
        }
        if (absolutePath.startsWith(LEGACY_PRIMARY_STORAGE)) {
            add("primary:" + absolutePath.removePrefix(LEGACY_PRIMARY_STORAGE))
        }
    }.distinct()
}

/** Comparable form of a document name: lowercase letters and digits only. */
internal fun String.normalizedKey(): String = lowercase().filter { it.isLetterOrDigit() }

/** Result of scanning the granted tree for RetroArch playlists. */
private data class PlaylistScan(
    val playlists: List<Uri>,
    /** Content history playlist (`content_history.lpl`), newest run first. */
    val history: Uri?,
)
