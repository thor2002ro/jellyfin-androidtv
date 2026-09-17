package org.jellyfin.androidtv

import android.app.Application
import android.content.Context
import org.jellyfin.androidtv.telemetry.TelemetryService
import org.jellyfin.androidtv.util.DeviceGraphicsInfoProvider
import org.jellyfin.playback.core.font.SubtitleFontProvider

class JellyfinApplication : Application() {
	override fun attachBaseContext(base: Context?) {
		super.attachBaseContext(base)
		TelemetryService.init(this)
	}

	override fun onCreate() {
		super.onCreate()
		SubtitleFontProvider.initialize(this)
		DeviceGraphicsInfoProvider.initialize(this)
	}
}
