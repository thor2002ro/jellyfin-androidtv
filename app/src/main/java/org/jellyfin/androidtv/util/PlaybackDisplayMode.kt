package org.jellyfin.androidtv.util

import android.app.Activity
import android.view.Display
import kotlin.math.abs

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

private fun matchesRate(rate: Float, currentRate: Float, sourceRate: Float?): Boolean =
	if (sourceRate == null) abs(rate - currentRate) < RATE_TOLERANCE
	else listOf(1f, 2f, PULLDOWN_MULTIPLIER).any { abs(rate - sourceRate * it) < RATE_TOLERANCE }

/** One window-level policy for all player backends. */
object PlaybackDisplayMode {
	@JvmStatic
	fun apply(
		activity: Activity, width: Int?, height: Int?, frameRate: Float?,
		switchRefreshRate: Boolean, switchResolution: Boolean, originalMode: Display.Mode,
	) {
		val display = activity.windowManager.defaultDisplay
		val mode = selectPlaybackDisplayMode(
			display.supportedModes, originalMode, width, height, frameRate, switchRefreshRate, switchResolution,
		) ?: originalMode
		val attributes = activity.window.attributes
		val preferredId = if (!switchRefreshRate && !switchResolution) 0 else mode.modeId
		if (attributes.preferredDisplayModeId == preferredId) return
		attributes.preferredDisplayModeId = preferredId
		activity.window.attributes = attributes
	}
}

private const val RATE_TOLERANCE = 0.01f
private const val MIN_HD_HEIGHT = 720
private const val PULLDOWN_MULTIPLIER = 2.5f
