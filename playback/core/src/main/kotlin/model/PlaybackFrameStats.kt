package org.jellyfin.playback.core.model

data class PlaybackFrameStats(
	val droppedFrames: Int,
	val corruptedFrames: Int,
) {
	companion object {
		val EMPTY = PlaybackFrameStats(
			droppedFrames = 0,
			corruptedFrames = 0,
		)
	}
}
