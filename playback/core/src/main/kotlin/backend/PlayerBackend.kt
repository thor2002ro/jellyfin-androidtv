package org.jellyfin.playback.core.backend

import org.jellyfin.playback.core.PlaybackBufferOptions
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.model.PlaybackFrameStats
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.support.PlaySupportReport
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView
import kotlin.time.Duration

data class VideoDecoderOption(
	val id: String,
	val label: String,
)

/**
 * Implementation for a media player backend. A backend is unaware of queues and can only play or
 * preload items.
 */
interface PlayerBackend {
	val reportsBufferedPosition: Boolean
		get() = true

	// Testing
	fun supportsStream(stream: MediaStream): PlaySupportReport

	// UI
	fun setSurfaceView(surfaceView: PlayerSurfaceView?)
	fun setSubtitleView(surfaceView: PlayerSubtitleView?)

	// Data retrieval

	fun setListener(eventListener: PlayerBackendEventListener?)
	fun getPositionInfo(): PositionInfo
	fun getFrameStats(): PlaybackFrameStats = PlaybackFrameStats.EMPTY

	// Mutation

	fun prepareItem(item: QueueEntry)
	fun playItem(item: QueueEntry)
	fun replaceItem(item: QueueEntry)

	fun setBufferOptions(options: PlaybackBufferOptions) = Unit

	fun onActivated() = Unit

	val videoDecoderOptions: List<VideoDecoderOption>
		get() = emptyList()
	val selectedVideoDecoderOption: VideoDecoderOption?
		get() = null
	val forcedVideoDecoderOption: VideoDecoderOption?
		get() = null

	fun setForcedVideoDecoderOption(option: VideoDecoderOption?) = Unit

	fun play()
	fun pause()
	fun stop()

	fun seekTo(position: Duration)
	fun setScrubbing(scrubbing: Boolean)

	fun setSpeed(speed: Float)

	val supportsSubtitleTimingSpeed: Boolean
		get() = true

	fun setSubtitleTiming(offset: Duration, speed: Float)

	fun setTimedEvents(timedEvents: List<TimedEvent>)
}
