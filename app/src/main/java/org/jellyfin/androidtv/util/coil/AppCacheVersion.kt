package org.jellyfin.androidtv.util.coil

import android.content.Context
import java.io.File
import kotlin.concurrent.thread

internal const val IMAGE_CACHE_DIRECTORY_NAME = "image_cache"

private const val APP_CACHE_VERSION_PREFERENCES_NAME = "app_cache_version"
private const val APP_CACHE_VERSION_CODE_KEY = "version_code"
private const val STALE_APP_CACHE_PREFIX = ".app-cache-stale-"

/**
 * Clears all disposable files in the app cache directory once when the installed app version changes.
 * The version marker is stored outside the cache directory so accounts and settings are untouched.
 */
fun clearAppCacheOnVersionChange(context: Context, currentVersionCode: Long) {
	val preferences = context.getSharedPreferences(APP_CACHE_VERSION_PREFERENCES_NAME, Context.MODE_PRIVATE)
	val previousVersionCode = if (preferences.contains(APP_CACHE_VERSION_CODE_KEY)) {
		preferences.getLong(APP_CACHE_VERSION_CODE_KEY, currentVersionCode)
	} else {
		null
	}

	clearCacheForVersionChange(
		previousVersionCode = previousVersionCode,
		currentVersionCode = currentVersionCode,
		clearCache = {
			detachCacheContents(
				cacheDirectory = context.cacheDir,
				staleCacheDirectory = context.cacheDir.resolve("$STALE_APP_CACHE_PREFIX${System.nanoTime()}"),
			)
		},
		recordCurrentVersion = { versionCode ->
			preferences.edit().putLong(APP_CACHE_VERSION_CODE_KEY, versionCode).commit()
		},
	)

	// Recursive deletion can be expensive; detached caches are no longer visible to cache consumers and can be removed off the startup thread.
	val staleCaches = context.cacheDir.listFiles { file -> file.name.startsWith(STALE_APP_CACHE_PREFIX) }.orEmpty()
	if (staleCaches.isNotEmpty()) thread(name = "app-cache-cleanup", isDaemon = true) {
		staleCaches.forEach(File::deleteRecursively)
	}
}

internal fun detachCacheContents(cacheDirectory: File, staleCacheDirectory: File): Boolean {
	val cacheEntries = cacheDirectory.listFiles { file -> !file.name.startsWith(STALE_APP_CACHE_PREFIX) }.orEmpty()
	if (cacheEntries.isEmpty()) return true
	if (!staleCacheDirectory.mkdir()) return false
	return cacheEntries.all { entry -> entry.renameTo(staleCacheDirectory.resolve(entry.name)) }
}

internal fun clearCacheForVersionChange(
	previousVersionCode: Long?,
	currentVersionCode: Long,
	clearCache: () -> Boolean,
	recordCurrentVersion: (Long) -> Boolean,
): Boolean {
	if (previousVersionCode == currentVersionCode) return false
	if (!clearCache()) return false
	return recordCurrentVersion(currentVersionCode)
}
