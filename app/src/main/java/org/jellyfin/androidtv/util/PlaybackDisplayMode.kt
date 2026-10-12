package org.jellyfin.androidtv.util

import android.app.Activity
import android.view.Display
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.getSystemService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun selectPlaybackDisplayMode(
	modes: Array<Display.Mode>, current: Display.Mode, width: Int?, height: Int?, frameRate: Float?,
	switchRefreshRate: Boolean, switchResolution: Boolean,
): Display.Mode? {
	if (!switchRefreshRate && !switchResolution) return null
	val sourceRate = frameRate?.takeIf { it.isFinite() && it > 0 }
	val targetWidth = width ?: 0
	val targetHeight = height ?: 0
	val matchResolution = switchResolution && targetWidth > 0 && targetHeight > 0
	return modes.filter { mode ->
		matchesResolution(mode, current, targetWidth, targetHeight, matchResolution) &&
			matchesRate(mode.refreshRate, current.refreshRate, sourceRate.takeIf { switchRefreshRate })
	}.minWithOrNull(compareBy<Display.Mode> {
		if (switchRefreshRate && sourceRate != null) abs(it.refreshRate - sourceRate) else 0f
	}.thenBy {
		if (matchResolution) abs(it.physicalWidth - targetWidth) + abs(it.physicalHeight - targetHeight) else 0
	}.thenBy { if (it.modeId == current.modeId) 0 else 1 })
}

private fun matchesResolution(mode: Display.Mode, current: Display.Mode, width: Int, height: Int, switching: Boolean): Boolean {
	val sameResolution = mode.physicalWidth == current.physicalWidth && mode.physicalHeight == current.physicalHeight
	return sameResolution || (switching && mode.physicalWidth >= width &&
		mode.physicalHeight >= height && mode.physicalHeight >= MIN_HD_HEIGHT)
}

private fun matchesRate(rate: Float, currentRate: Float, sourceRate: Float?): Boolean {
	if (sourceRate == null) return abs(rate - currentRate) < RATE_TOLERANCE
	// Whole-number multiples present every source frame for the same duration.
	// 23.976 -> 59.94 is 3:2 pulldown, not a cadence match. Accepting it hid
	// unavailable film modes and could upscale DV into an incompatible 4K60 mode.
	val multiple = (rate / sourceRate).roundToInt()
	return multiple >= 1 && abs(rate - sourceRate * multiple) < RATE_TOLERANCE
}

/** One window-level policy for all player backends. */
object PlaybackDisplayMode {
	@JvmStatic
	fun delayMillisForModeChange(currentModeId: Int, requestedModeId: Int, delaySeconds: Int): Long =
		if (currentModeId == requestedModeId) 0L else delaySeconds.coerceIn(0, 5) * 1_000L

	@JvmStatic
	fun frameRateForDisplay(frameRate: Float?, isInterlaced: Boolean?): Float? =
		if (isInterlaced == true) frameRate?.times(2) else frameRate

	/** Request a mode and return its ID. Updating window attributes does not await the switch. */
	@JvmStatic
	fun apply(
		activity: Activity, width: Int?, height: Int?, frameRate: Float?,
		switchRefreshRate: Boolean, switchResolution: Boolean, originalMode: Display.Mode,
	): Int {
		val display = activity.windowManager.defaultDisplay
		val modes = display.supportedModes
		val selected = selectPlaybackDisplayMode(
			modes, originalMode, width, height, frameRate, switchRefreshRate, switchResolution,
		)
		val mode = selected ?: originalMode
		Timber.i(
			"Display selection: video=%sx%s@%s refresh=%s resolution=%s selected=%s available=%s",
			width, height, frameRate, switchRefreshRate, switchResolution, mode, modes.joinToString(),
		)
		if (selected == null && switchRefreshRate && frameRate != null && frameRate.isFinite() && frameRate > 0) {
			Timber.w("No cadence-compatible display mode for %s fps; retaining original mode. On Fire TV, check Match Original Frame Rate in system display settings.", frameRate)
		}
		val attributes = activity.window.attributes
		val preferredId = if (!switchRefreshRate && !switchResolution) 0 else mode.modeId
		if (attributes.preferredDisplayModeId == preferredId) return mode.modeId
		attributes.preferredDisplayModeId = preferredId
		activity.window.attributes = attributes
		return mode.modeId
	}

	/**
	 * Wait for Android to report the requested mode before the caller opens video.
	 * This prevents display switching from overlapping MPV decoder startup, where
	 * Dolby Vision output may be affected by the transition. Mode activation does
	 * not prove DV support or confirm that the TV has finished its HDMI handshake.
	 * A configurable settling delay follows confirmation when the mode changes.
	 *
	 * False rejects startup on timeout or when the screen is no longer current.
	 * Coroutine cancellation propagates after listener cleanup.
	 */
	suspend fun applyAndAwait(
		activity: Activity, width: Int?, height: Int?, frameRate: Float?,
		switchRefreshRate: Boolean, switchResolution: Boolean, originalMode: Display.Mode,
		originalPreference: Int = 0,
		delaySeconds: Int = 2,
		isCurrent: () -> Boolean = { true },
	): Boolean = withContext(Dispatchers.Main.immediate) {
		// Window writes and display callbacks share the main thread. Suspending below
		// leaves that thread free to receive the event that completes the wait.
		if (activity.isFinishing || activity.isDestroyed || !isCurrent()) return@withContext false
		val display = activity.windowManager.defaultDisplay
		val currentModeId = display.mode.modeId
		if (!switchRefreshRate && !switchResolution && display.mode.modeId == originalMode.modeId &&
			activity.window.attributes.preferredDisplayModeId == originalPreference
		) return@withContext true
		val displayManager = requireNotNull(activity.getSystemService<DisplayManager>())
		val ready = CompletableDeferred<Unit>()
		val previousPreference = activity.window.attributes.preferredDisplayModeId
		var switched = false
		var requestedId = -1
		val listener = object : DisplayManager.DisplayListener {
			override fun onDisplayAdded(displayId: Int) = Unit
			override fun onDisplayRemoved(displayId: Int) = Unit
			override fun onDisplayChanged(displayId: Int) {
				if (displayId == display.displayId && display.mode.modeId == requestedId) ready.complete(Unit)
			}
		}
		// Subscribe before requesting the mode so a fast change cannot be missed.
		displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
		try {
			requestedId = if (switchRefreshRate || switchResolution) {
				apply(activity, width, height, frameRate, switchRefreshRate, switchResolution, originalMode)
			} else {
				// Settings may have disabled switching since the previous item. Restore
				// the original physical mode and await it before opening the next stream.
				// The original window preference (including zero) is restored on screen exit.
				val attributes = activity.window.attributes
				attributes.preferredDisplayModeId = originalMode.modeId
				activity.window.attributes = attributes
				originalMode.modeId
			}
			// An already-active mode may produce no display event. Read it after the
			// request too, covering both that case and a change that completed immediately.
			if (display.mode.modeId == requestedId) ready.complete(Unit)
			// Bound the mode confirmation wait separately from the TV settling delay.
			switched = withTimeoutOrNull(5_000L) {
				ready.await()
				true
			} == true
			if (!switched) {
				Timber.w("Display mode %d did not become active; skipping playback startup", requestedId)
			}
			if (switched) delay(delayMillisForModeChange(currentModeId, requestedId, delaySeconds))
			switched && !activity.isFinishing && !activity.isDestroyed && isCurrent()
		} finally {
			displayManager.unregisterDisplayListener(listener)
			// Roll back only while this screen still owns this request. A newer screen
			// or a different requested mode must not be overwritten by late cleanup.
			if (!switched && isCurrent() && activity.window.attributes.preferredDisplayModeId == requestedId) {
				val attributes = activity.window.attributes
				attributes.preferredDisplayModeId = previousPreference
				activity.window.attributes = attributes
			}
		}
	}
}

private const val RATE_TOLERANCE = 0.01f
private const val MIN_HD_HEIGHT = 720
