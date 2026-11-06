package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.VideoSize
import org.jellyfin.playback.core.model.VideoGeometry

internal fun VideoSize.toVideoGeometry(): VideoGeometry {
	if (this == VideoSize.UNKNOWN || width <= 0 || height <= 0) return VideoGeometry.EMPTY

	val displayAspect = pixelWidthHeightRatio
		.takeIf { it.isFinite() && it > 0f }
		?.let { ratio -> width.toDouble() * ratio.toDouble() / height.toDouble() }
		?.takeIf { it.isFinite() && it > 0.0 && it <= Float.MAX_VALUE.toDouble() }
		?.toFloat()

	return VideoGeometry(
		frameWidth = width,
		frameHeight = height,
		displayAspectRatio = displayAspect,
	)
}
