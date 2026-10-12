package org.jellyfin.androidtv

import android.app.Application
import android.content.Context
import org.jellyfin.androidtv.telemetry.TelemetryService
import org.jellyfin.androidtv.util.DeviceGraphicsInfoProvider
import org.jellyfin.androidtv.util.coil.clearAppCacheOnVersionChange
import org.jellyfin.playback.core.font.SubtitleFontProvider

class JellyfinApplication : Application() {
	override fun attachBaseContext(base: Context?) {
		super.attachBaseContext(base)
		// Run before app providers can open cache files created by an older build.
		clearAppCacheOnVersionChange(this, BuildConfig.VERSION_CODE.toLong())
		TelemetryService.init(this)
	}

	override fun onCreate() {
		super.onCreate()
		SubtitleFontProvider.initialize(this)
		DeviceGraphicsInfoProvider.initialize(this)
	}
}
