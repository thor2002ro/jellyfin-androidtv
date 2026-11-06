package org.jellyfin.androidtv.ui.playback.overlay

import androidx.leanback.widget.PlaybackSeekDataProvider

class CustomSeekProvider(
	private val videoPlayerAdapter: VideoPlayerAdapter,
	private val interval: Long
) : PlaybackSeekDataProvider() {
	private var currentSeekPositions = LongArray(0)

	override fun getSeekPositions(): LongArray {
		if (!videoPlayerAdapter.canSeek()) return LongArray(0)

		val currentSeekPosition = videoPlayerAdapter.currentPosition
		val videoEndPosition = videoPlayerAdapter.duration

		val firstSeekPosition = currentSeekPosition % interval
		val lastSeekPosition = videoEndPosition - ((videoEndPosition - currentSeekPosition) % interval)
		val seekPositionCount = ((lastSeekPosition - firstSeekPosition) / interval).toInt() + 1

		val seekPositions = ArrayList<Long>(seekPositionCount + 2) // intermediate seek positions + beginning + end position
		seekPositions.add(0L)
		// Omit the first seek position if it represents the beginning of the video
		if (firstSeekPosition != 0L) {
			seekPositions.add(firstSeekPosition)
		}
		// Add all available seek positions but the last one
		for (i in 1..<seekPositionCount - 1) {
			seekPositions.add(firstSeekPosition + (i * interval))
		}
		// Omit the last seek position if it represents the end of the video
		if (lastSeekPosition != videoEndPosition) {
			seekPositions.add(lastSeekPosition)
		}
		// Omit the video end seek position if it represents the beginning of the video
		if (videoEndPosition != 0L) {
			seekPositions.add(videoEndPosition)
		}

		currentSeekPositions = seekPositions.toLongArray()
		return currentSeekPositions
	}

	override fun reset() {
		currentSeekPositions = LongArray(0)
	}
}
