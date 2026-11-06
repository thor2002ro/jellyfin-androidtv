package org.jellyfin.playback.jellyfin.livetv

import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.liveStreamTargetOffset
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class LiveTvPlaybackPolicy(
	private val directPlayEnabled: () -> Boolean = { true },
) {
	companion object {
		val INITIAL_LIVE_STREAM_TARGET_OFFSET = 5.seconds
		private val LIVE_STREAM_TARGET_OFFSET_STEP = 5.seconds
		private val MAX_LIVE_STREAM_TARGET_OFFSET = 30.seconds
	}

	data class PlaybackOptions(
		val enableDirectPlay: Boolean,
		val enableDirectStream: Boolean,
		val autoOpenLiveStream: Boolean,
	)

	fun isLiveTv(item: BaseItemDto): Boolean = when (item.type) {
		BaseItemKind.PROGRAM,
		BaseItemKind.TV_PROGRAM,
		BaseItemKind.TV_CHANNEL,
		BaseItemKind.LIVE_TV_PROGRAM,
		BaseItemKind.LIVE_TV_CHANNEL -> true

		else -> false
	}

	fun getPlaybackOptions(item: BaseItemDto): PlaybackOptions {
		if (!isLiveTv(item)) {
			return PlaybackOptions(
				enableDirectPlay = true,
				enableDirectStream = true,
				autoOpenLiveStream = false,
			)
		}

		return PlaybackOptions(
			enableDirectPlay = directPlayEnabled(),
			enableDirectStream = true,
			autoOpenLiveStream = true,
		)
	}

	fun increaseLiveStreamTargetOffset(entry: QueueEntry): Duration? {
		val item = entry.baseItem ?: return null
		if (!isLiveTv(item)) return null

		val currentOffset = entry.liveStreamTargetOffset ?: INITIAL_LIVE_STREAM_TARGET_OFFSET
		val nextOffset = (currentOffset + LIVE_STREAM_TARGET_OFFSET_STEP).coerceAtMost(MAX_LIVE_STREAM_TARGET_OFFSET)
		entry.liveStreamTargetOffset = nextOffset

		Timber.i("Set Live TV target offset to $nextOffset for item ${item.id}")
		return nextOffset
	}
}
