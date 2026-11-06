package org.jellyfin.androidtv.test

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager

/** A blank video host for device tests, without browsing or server session reporting. */
class PlaybackTestActivity : Activity() {
	private var displayWakeLock: PowerManager.WakeLock? = null

	@Suppress("DEPRECATION")
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		displayWakeLock = getSystemService(PowerManager::class.java).newWakeLock(
			PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
			"jellyfin-androidtv:playback-device-tests",
		).apply { acquire(10 * 60 * 1000L) }
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setTurnScreenOn(true)
	}

	override fun onDestroy() {
		displayWakeLock?.takeIf(PowerManager.WakeLock::isHeld)?.release()
		displayWakeLock = null
		super.onDestroy()
	}
}
