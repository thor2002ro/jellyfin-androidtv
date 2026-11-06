package org.jellyfin.androidtv.util

import android.content.Context
import android.util.Log
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.preference.UserPreferences
import timber.log.Timber

object AppLogging {
	private var logcatTree: Timber.Tree? = null

	fun configure(context: Context) {
		val userPreferences = UserPreferences(context.applicationContext)
		setVerboseLogging(
			enabled = userPreferences[UserPreferences.verboseLoggingEnabled],
		)
	}

	fun setVerboseLogging(
		enabled: Boolean,
	) {
		logcatTree?.let(Timber::uproot)
		logcatTree = null

		LogcatTree(minPriority = if (enabled) Log.VERBOSE else Log.INFO)
			.also(Timber::plant)
			.also { logcatTree = it }

		if (enabled) {
			enableCloseGuard()
			Timber.i("Verbose app logging enabled")
		} else {
			Timber.i("App logging enabled")
		}
	}

	private fun enableCloseGuard() {
		if (!BuildConfig.DEBUG) return

		try {
			Class.forName("dalvik.system.CloseGuard")
				.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
				.invoke(null, true)
		} catch (e: ReflectiveOperationException) {
			@Suppress("TooGenericExceptionThrown")
			throw RuntimeException(e)
		}
	}

	private class LogcatTree(
		private val minPriority: Int,
	) : Timber.DebugTree() {
		override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= minPriority
	}
}
