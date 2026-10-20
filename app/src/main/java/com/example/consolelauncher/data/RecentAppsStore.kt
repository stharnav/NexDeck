package com.example.consolelauncher.data

import android.content.Context

/**
 * Keeps the launch history of the launcher.
 *
 * The platform "recent tasks" API is restricted for third party apps, so the launcher tracks
 * the apps it started itself. The history is stored as a single newline separated string so
 * that the ordering survives SharedPreferences round trips.
 */
class RecentAppsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recentPackages(): List<String> =
        prefs.getString(KEY_PACKAGES, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    fun record(packageName: String) {
        val updated = (listOf(packageName) + recentPackages().filter { it != packageName })
            .take(MAX_ENTRIES)
        // Commit synchronously: the launcher process can be killed as soon as another app is
        // started, and an asynchronous write would be lost.
        prefs.edit().putString(KEY_PACKAGES, updated.joinToString("\n")).commit()
    }

    private companion object {
        const val PREFS_NAME = "console_launcher_recent"
        const val KEY_PACKAGES = "packages"
        const val MAX_ENTRIES = 24
    }
}
