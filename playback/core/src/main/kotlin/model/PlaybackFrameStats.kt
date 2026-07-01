package org.jellyfin.playback.core.model

data class PlaybackFrameStats(
	val droppedFrames: Int,
	val corruptedFrames: Int,
	val videoDecodedFrames: Int = 0,
	val videoDecoderName: String? = null,
	val audioDecoderName: String? = null,
	val audioPassthroughSupported: Boolean? = null,
) {
	companion object {
		val EMPTY = PlaybackFrameStats(
			droppedFrames = 0,
			corruptedFrames = 0,
		)
	}
}
