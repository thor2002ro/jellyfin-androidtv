package org.jellyfin.playback.core.model

data class PlaybackFrameStats(
	val droppedFrames: Int,
	val corruptedFrames: Int,
	val videoDecoderName: String? = null,
	val audioDecoderName: String? = null,
) {
	companion object {
		val EMPTY = PlaybackFrameStats(
			droppedFrames = 0,
			corruptedFrames = 0,
		)
	}
}
