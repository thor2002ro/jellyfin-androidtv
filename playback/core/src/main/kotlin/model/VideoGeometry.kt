package org.jellyfin.playback.core.model

data class VideoGeometry(
	val frameWidth: Int,
	val frameHeight: Int,
	val displayAspectRatio: Float?,
) {
	val frameAspectRatio: Float
		get() {
			if (frameWidth <= 0 || frameHeight <= 0) return 0f
			return (frameWidth.toDouble() / frameHeight.toDouble())
				.takeIf { it.isFinite() && it > 0.0 && it <= Float.MAX_VALUE.toDouble() }
				?.toFloat()
				?: 0f
		}

	val videoAspectRatio: Float
		get() = displayAspectRatio
			?.takeIf { it.isFinite() && it > 0f }
			?: frameAspectRatio

	companion object {
		val EMPTY = VideoGeometry(0, 0, null)
	}
}
