package com.example.consolelauncher.data

import android.content.Context

/**
 * Launcher-wide feature switches persisted locally. Today it only holds the RetroArch
 * "ambient mode" flag; the toggles on the SETTINGS/RETROARCH tab grow with it.
 *
 * The same synchronous write policy as [UserProfileStore] applies: the launcher process can die
 * at any moment, so changes are committed instead of applied.
 */
class LauncherSettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the RetroArch ambient mode switch is on. */
    fun ambientMode(): Boolean = prefs.getBoolean(KEY_AMBIENT_MODE, false)

    fun setAmbientMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AMBIENT_MODE, enabled).commit()
    }

    private companion object {
        const val PREFS_NAME = "console_launcher_settings"
        const val KEY_AMBIENT_MODE = "retroarch_ambient_mode"
    }
}
