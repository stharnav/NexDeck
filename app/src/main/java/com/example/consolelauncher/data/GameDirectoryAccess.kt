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

/**
 * Manages access to a user-selected game/ROM directory via Storage Access Framework (SAF).
 * Uses ACTION_OPEN_DOCUMENT_TREE to let user grant access to a directory containing ROMs.
 */
class GameDirectoryAccess(context: Context) {

    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "GameDirectoryAccess"
        private const val PREFS_NAME = "game_directory_access"
        private const val KEY_GAME_DIR_URI = "game_dir_tree_uri"
        
        // Common ROM file extensions
        private val ROM_EXTENSIONS = setOf(
            ".zip", ".7z", ".rar",           // Archives
            ".gba", ".gb", ".gbc",           // Game Boy
            ".nes", ".fds",                  // NES
            ".sfc", ".smc",                  // SNES
            ".n64", ".z64", ".v64",          // N64
            ".nds",                          // Nintendo DS
            ".3ds", ".cia",                  // 3DS
            ".gcm", ".iso", ".wbfs",         // GameCube/Wii
            ".iso", ".cso", ".pbp",          // PSP
            ".chd",                          // CHD
            ".cue", ".bin",                  // PS1/Sega CD
            ".md", ".gen", ".smd",           // Genesis
            ".pce", ".sgx",                  // PC Engine
            ".ws", ".wsc",                   // WonderSwan
            ".ngp", ".ngc",                  // Neo Geo Pocket
            ".a26", ".a78",                  // Atari
            ".col",                          // ColecoVision
            ".int",                          // Intellivision
            ".sms", ".gg",                   // Master System/Game Gear
            ".vec",                          // Vectrex
            ".rom",                          // Generic
        )
    }

    /**
     * Checks if we have persisted access to the game directory.
     */
    fun hasAccess(): Boolean {
        val uriString = prefs.getString(KEY_GAME_DIR_URI, null)
        return uriString != null
    }

    /**
     * Gets the persisted game directory URI.
     */
    fun getGameDirUri(): Uri? {
        val uriString = prefs.getString(KEY_GAME_DIR_URI, null)
        return uriString?.let { Uri.parse(it) }
    }

    /**
     * Launches the SAF directory picker to let user grant access to a game/ROM directory.
     * The caller should pass an ActivityResultLauncher<Intent> for the result.
     */
    fun requestAccess(launcher: ActivityResultLauncher<Intent>) {
        Log.d(TAG, "Requesting SAF access to game directory")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
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
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    prefs.edit().putString(KEY_GAME_DIR_URI, uri.toString()).apply()
                    Log.d(TAG, "Persisted access to game directory: $uri")
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
        val uri = getGameDirUri()
        uri?.let {
            contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().remove(KEY_GAME_DIR_URI).apply()
    }

    /**
     * Lists all ROM files in the game directory (recursively).
     */
    suspend fun listRomFiles(): List<Uri> = withContext(Dispatchers.IO) {
        val gameDirUri = getGameDirUri() ?: return@withContext emptyList()
        
        val uris = mutableListOf<Uri>()
        scanDirectoryRecursive(gameDirUri, DocumentsContract.getTreeDocumentId(gameDirUri), uris)
        uris
    }

    /**
     * Recursively scans a directory for ROM files.
     */
    private fun scanDirectoryRecursive(treeUri: Uri, documentId: String, uris: MutableList<Uri>) {
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_FLAGS
                ),
                null, null, null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex)
                    val mime = cursor.getString(mimeIndex)
                    val flags = cursor.getInt(flagsIndex)
                    
                    if (id == null || name == null) continue
                    
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    
                    // Check if it's a directory
                    val isDirectory = (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0 ||
                                     mime == DocumentsContract.Document.MIME_TYPE_DIR
                    
                    if (isDirectory) {
                        // Recurse into subdirectory
                        scanDirectoryRecursive(treeUri, id, uris)
                    } else if (isRomFile(name)) {
                        uris.add(childUri)
                        Log.d(TAG, "Found ROM: $name")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan directory: $documentId", e)
        }
    }

    /**
     * Checks if a file is a ROM based on extension.
     */
    private fun isRomFile(fileName: String): Boolean {
        val lowerName = fileName.lowercase()
        return ROM_EXTENSIONS.any { lowerName.endsWith(it) }
    }

    /**
     * Gets the display name for a SAF document URI.
     */
    suspend fun getDisplayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get display name for $uri", e)
            null
        }
    }
}