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

        private const val PRIMARY_STORAGE = "/storage/emulated/0/"
        private const val LEGACY_PRIMARY_STORAGE = "/sdcard/"
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
     */
    fun requestAccess(launcher: ActivityResultLauncher<Intent>) {
        Log.d(TAG, "Requesting SAF access to RetroArch playlists")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // Pre-select RetroArch's documents provider so the picker opens on its data directory.
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://$RETROARCH_DOCUMENTS_AUTHORITY/"),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        launcher.launch(intent)
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
                            collectPlaylists(treeUri, id, depth + 1, result)
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
