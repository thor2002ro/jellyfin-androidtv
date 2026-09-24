package org.jellyfin.playback.jellyfin.livetv

import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.liveStreamTargetOffset
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class LiveTvPlaybackPolicy(
	private val directPlayEnabled: () -> Boolean = { true },
) {
	companion object {
		val INITIAL_LIVE_STREAM_TARGET_OFFSET = 5.seconds
		private val LIVE_STREAM_TARGET_OFFSET_STEP = 5.seconds
		private val MAX_LIVE_STREAM_TARGET_OFFSET = 15.seconds
	}

	data class PlaybackOptions(
		val enableDirectPlay: Boolean,
		val enableDirectStream: Boolean,
		val autoOpenLiveStream: Boolean,
	)

	private val errorCounts = ConcurrentHashMap<UUID, Int>()

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

		val errorCount = errorCounts[item.id] ?: 0
		return PlaybackOptions(
			enableDirectPlay = directPlayEnabled() && errorCount == 0,
			enableDirectStream = errorCount <= 1,
			autoOpenLiveStream = true,
		)
	}

	fun shouldRetryWithFallback(entry: QueueEntry): Boolean {
		val item = entry.baseItem ?: return false
		if (!isLiveTv(item)) return false

		val errorCount = errorCounts[item.id] ?: 0
		if (errorCount >= 2) {
			Timber.w("Live TV playback fallback exhausted for item ${item.id}; not retrying")
			return false
		}

		val nextErrorCount = errorCount + 1
		errorCounts[item.id] = nextErrorCount
		entry.liveStreamTargetOffset = getLiveStreamTargetOffset(nextErrorCount)
		Timber.i("Retrying Live TV playback with fallback level $nextErrorCount and target offset ${entry.liveStreamTargetOffset}")
		return true
	}

	fun reset(item: BaseItemDto?, reason: String? = null) {
		val id = item?.id ?: return
		val previousErrorCount = errorCounts.remove(id) ?: return
		Timber.i("Reset Live TV playback fallback for item $id after $previousErrorCount errors${reason?.let { " ($it)" } ?: ""}")
	}

	private fun getLiveStreamTargetOffset(errorCount: Int): Duration =
		(INITIAL_LIVE_STREAM_TARGET_OFFSET + (LIVE_STREAM_TARGET_OFFSET_STEP * errorCount))
			.coerceAtMost(MAX_LIVE_STREAM_TARGET_OFFSET)
}
