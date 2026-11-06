package org.jellyfin.androidtv.util

import android.content.Context
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.preference.UserPreferences
import timber.log.Timber

object AppLogging {
	private var debugTree: Timber.Tree? = null

	fun configure(context: Context) {
		val userPreferences = UserPreferences(context.applicationContext)
		setVerboseLogging(
			context = context,
			enabled = userPreferences[UserPreferences.verboseLoggingEnabled],
		)
	}

	fun setVerboseLogging(
		context: Context,
		enabled: Boolean,
	) {
		debugTree?.let(Timber::uproot)
		debugTree = null

		if (!enabled) return

		Timber.DebugTree()
			.also(Timber::plant)
			.also { debugTree = it }

		enableCloseGuard()
		Timber.i("Debug tree planted")
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
}
