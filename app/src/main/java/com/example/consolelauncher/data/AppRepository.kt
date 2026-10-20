package com.example.consolelauncher.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Reads the launchable applications installed on the device. */
class AppRepository(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun loadLaunchableApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val ownPackage = appContext.packageName
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        packageManager.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo.packageName }
            .filter { it != ownPackage }
            .distinct()
            .mapNotNull { pkg -> entryFor(pkg) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun entryFor(pkg: String): AppEntry? = runCatching {
        val label = packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
            .toString()
            .ifBlank { pkg }
        AppEntry(pkg, label, iconFor(pkg))
    }.getOrNull()

    private fun iconFor(pkg: String): ImageBitmap =
        iconCache.getOrPut(pkg) {
            packageManager.getApplicationIcon(pkg).toIconBitmap()
        }

    private fun Drawable.toIconBitmap(): ImageBitmap {
        val width = if (intrinsicWidth > 0) intrinsicWidth else FALLBACK_ICON_PX
        val height = if (intrinsicHeight > 0) intrinsicHeight else FALLBACK_ICON_PX
        return toBitmap(width = width, height = height, config = Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }

    private companion object {
        const val FALLBACK_ICON_PX = 192
    }
}
