package org.jellyfin.playback.jellyfin.livetv

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.liveStreamTargetOffset
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem

class LiveTvPlaybackResetService(
	private val liveTvPlaybackPolicy: LiveTvPlaybackPolicy,
) : PlayerService() {
	private var currentEntry: QueueEntry? = null

	override suspend fun onInitialize() {
		manager.queue.entry.onEach { entry ->
			if (entry == null) {
				currentEntry = null
				return@onEach
			}

			if (entry === currentEntry) return@onEach
			currentEntry = entry

			val item = entry.baseItem ?: return@onEach
			if (!liveTvPlaybackPolicy.isLiveTv(item)) return@onEach

			liveTvPlaybackPolicy.reset(item, reason = "fresh queue entry")
			entry.liveStreamTargetOffset = LiveTvPlaybackPolicy.INITIAL_LIVE_STREAM_TARGET_OFFSET
		}.launchIn(coroutineScope)
	}
}
