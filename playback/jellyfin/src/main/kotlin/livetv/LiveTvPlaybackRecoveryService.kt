package org.jellyfin.playback.jellyfin.livetv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.forceTranscoding
import org.jellyfin.playback.jellyfin.queue.forceTranscodingRecoveryAttempts
import timber.log.Timber

class LiveTvPlaybackRecoveryService(
	private val liveTvPlaybackPolicy: LiveTvPlaybackPolicy,
) : PlayerService() {
	override suspend fun onInitialize() {
		state.playState.onEach { playState ->
			if (playState != PlayState.ERROR) return@onEach

			val entry = manager.queue.entry.value ?: return@onEach
			if (entry.forceTranscoding == true) {
				val attempts = entry.forceTranscodingRecoveryAttempts ?: 0
				if (attempts >= MAX_FORCE_TRANSCODING_RECOVERY_ATTEMPTS) {
					Timber.w("Live TV quality-forced transcoding recovery exhausted; not applying direct play fallback")
					return@onEach
				}

				entry.forceTranscodingRecoveryAttempts = attempts + 1
				coroutineScope.launch(Dispatchers.Main) {
					Timber.i("Reloading Live TV quality-forced transcode after playback error")
					val reloaded = manager.reloadCurrentMediaStream(position = null, playWhenReady = true)
					if (reloaded) {
						Timber.i("Reloaded Live TV quality-forced transcode after playback error")
					} else {
						Timber.w("Unable to reload Live TV quality-forced transcode after playback error")
					}
				}
				return@onEach
			}
			if (!liveTvPlaybackPolicy.shouldRetryWithFallback(entry)) return@onEach

			coroutineScope.launch(Dispatchers.Main) {
				Timber.i("Reloading Live TV queue entry after playback error")
				val reloaded = manager.reloadCurrentMediaStream(position = null, playWhenReady = true)
				if (reloaded) {
					Timber.i("Reloaded Live TV queue entry after playback error")
				} else {
					Timber.w("Unable to reload Live TV queue entry after playback error")
				}
			}
		}.launchIn(coroutineScope)
	}

	private companion object {
		private const val MAX_FORCE_TRANSCODING_RECOVERY_ATTEMPTS = 1
	}
}
