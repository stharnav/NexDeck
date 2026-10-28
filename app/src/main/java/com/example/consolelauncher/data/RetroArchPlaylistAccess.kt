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

        /** RetroArch folder that holds the cover art of a system. */
        private const val BOXARTS_DIR = "Named_Boxarts"
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
    suspend fun listPlaylistFiles(): List<Uri> = withContext(Dispatchers.IO) {
        val treeUri = getPlaylistsUri() ?: return@withContext emptyList()
        val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext emptyList()

        val playlists = mutableListOf<Uri>()
        collectPlaylists(treeUri, rootDocumentId, 0, playlists)
        Log.d(TAG, "Found ${playlists.size} playlist files")
        playlists
    }

    private fun collectPlaylists(
        treeUri: Uri,
        documentId: String,
        depth: Int,
        result: MutableList<Uri>,
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
                                collectPlaylists(treeUri, id, depth + 1, result)
                            }
                        }

                        name.endsWith(".lpl", ignoreCase = true) &&
                            !name.startsWith("content_", ignoreCase = true) -> {
                            // Built-in history playlists (content_history.lpl, ...) are skipped.
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
<<<<<<< HEAD
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
=======
     * Lists the cover art of a system's `thumbnails/<system>/Named_Boxarts` folder, mapped from
     * the file base name (case/spacing-insensitive) to its document URI. Returns an empty map
     * when the granted tree does not contain the folder, e.g. when only `playlists` was picked.
     */
    suspend fun listBoxArt(playlistName: String): Map<String, Uri> = withContext(Dispatchers.IO) {
        val treeUri = getPlaylistsUri() ?: return@withContext emptyMap()
        val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext emptyMap()

        val thumbnailsId = childDirectoryId(treeUri, rootDocumentId, "thumbnails")
        if (thumbnailsId == null) {
            Log.d(TAG, "No thumbnails folder below the granted tree root: $rootDocumentId")
            return@withContext emptyMap()
        }

        val systemId = childDirectoryId(treeUri, thumbnailsId, playlistName)
            ?: childDirectoryIdNormalized(treeUri, thumbnailsId, playlistName)
        if (systemId == null) {
            Log.d(TAG, "No '$playlistName' folder below the thumbnails folder")
            return@withContext emptyMap()
        }

        val boxArtsId = childDirectoryId(treeUri, systemId, BOXARTS_DIR)
        if (boxArtsId == null) {
            Log.d(TAG, "No $BOXARTS_DIR folder below the '$playlistName' thumbnails")
            return@withContext emptyMap()
        }

        val artByBaseName = mutableMapOf<String, Uri>()
        listDocuments(treeUri, boxArtsId) { id, displayName, isDirectory ->
            if (!isDirectory) {
                artByBaseName[displayName.substringBeforeLast('.').normalizedKey()] =
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            }
            null
        }
        Log.d(TAG, "Found ${artByBaseName.size} box art files for '$playlistName'")
        artByBaseName
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
>>>>>>> 9e1d047 (fixed card icon bugs)
        }
    }

    /**
<<<<<<< HEAD
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
=======
>>>>>>> 9e1d047 (fixed card icon bugs)
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
