package org.jellyfin.androidtv

import android.content.Context
import androidx.startup.Initializer
import org.jellyfin.androidtv.util.AppLogging

class LogInitializer : Initializer<Unit> {
	override fun create(context: Context) {
		AppLogging.configure(context)
	}

	override fun dependencies() = emptyList<Class<out Initializer<*>>>()
}
