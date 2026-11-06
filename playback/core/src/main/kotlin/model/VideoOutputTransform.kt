package org.jellyfin.playback.core.model

data class VideoAspectRatio(
	val width: Int,
	val height: Int,
) {
	init {
		require(width > 0) { "Video aspect width must be positive" }
		require(height > 0) { "Video aspect height must be positive" }
	}
}

data class VideoOutputTransform(
	val aspectRatioOverride: VideoAspectRatio?,
) {
	companion object {
		val NONE = VideoOutputTransform(null)
	}
}
