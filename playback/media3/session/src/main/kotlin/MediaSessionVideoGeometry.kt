package org.jellyfin.playback.media3.session

import androidx.media3.common.VideoSize
import org.jellyfin.playback.core.model.VideoGeometry

internal fun VideoGeometry.toMedia3VideoSize(): VideoSize {
	if (frameWidth <= 0 || frameHeight <= 0) return VideoSize.UNKNOWN

	val frameAspect = frameWidth.toDouble() / frameHeight.toDouble()
	val displayAspect = displayAspectRatio
		?.toDouble()
		?.takeIf { it.isFinite() && it > 0.0 }
	val pixelRatio = displayAspect
		?.let { it / frameAspect }
		?.takeIf { it.isFinite() && it > 0.0 && it <= Float.MAX_VALUE.toDouble() }
		?.toFloat()
		?: 1f

	return VideoSize(frameWidth, frameHeight, pixelRatio)
}
