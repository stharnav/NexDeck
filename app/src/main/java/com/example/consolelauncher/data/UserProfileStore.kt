package com.example.consolelauncher.data

import android.content.Context

/**
 * Keeps the profile the user set up for this launcher, currently only the display name.
 *
 * The same synchronous write policy as [RecentAppsStore] applies: the launcher process can die
 * at any moment, so the name is committed instead of applied.
 */
class UserProfileStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The saved name, or the empty string when the user has not set one up yet. */
    fun name(): String = prefs.getString(KEY_NAME, null).orEmpty()

    fun save(name: String) {
        prefs.edit().putString(KEY_NAME, name.trim()).commit()
    }

    private companion object {
        const val PREFS_NAME = "console_launcher_profile"
        const val KEY_NAME = "user_name"
    }
}
