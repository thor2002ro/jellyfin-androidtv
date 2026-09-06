package org.jellyfin.playback.jellyfin.livetv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.forceTranscoding
import org.jellyfin.playback.jellyfin.queue.forceTranscodingRecoveryAttempts
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

class LiveTvPlaybackRecoveryService(
	private val liveTvPlaybackPolicy: LiveTvPlaybackPolicy,
) : PlayerService() {
	private var stalledBufferRecoveryJob: Job? = null

	override suspend fun onInitialize() {
		state.playState.onEach { playState ->
			when (playState) {
				PlayState.ERROR -> {
					cancelStalledBufferRecovery()
					recoverPlaybackError()
				}

				PlayState.BUFFERING -> startStalledBufferRecovery()

				PlayState.STOPPED,
				PlayState.PLAYING,
				PlayState.PAUSED -> cancelStalledBufferRecovery()
			}
		}.launchIn(coroutineScope)
	}

	private fun recoverPlaybackError() {
		val entry = manager.queue.entry.value ?: return
		if (entry.forceTranscoding == true) {
			val attempts = entry.forceTranscodingRecoveryAttempts ?: 0
			if (attempts >= MAX_FORCE_TRANSCODING_RECOVERY_ATTEMPTS) {
				Timber.w("Live TV quality-forced transcoding recovery exhausted; not applying direct play fallback")
				return
			}

			entry.forceTranscodingRecoveryAttempts = attempts + 1
			coroutineScope.launch(Dispatchers.Main) {
				reloadCurrentLiveTvStream(
					startMessage = "Reloading Live TV quality-forced transcode after playback error",
					successMessage = "Reloaded Live TV quality-forced transcode after playback error",
					failureMessage = "Unable to reload Live TV quality-forced transcode after playback error",
				)
			}
			return
		}
		if (!liveTvPlaybackPolicy.shouldRetryWithFallback(entry)) return

		coroutineScope.launch(Dispatchers.Main) {
			reloadCurrentLiveTvStream(
				startMessage = "Reloading Live TV queue entry after playback error",
				successMessage = "Reloaded Live TV queue entry after playback error",
				failureMessage = "Unable to reload Live TV queue entry after playback error",
			)
		}
	}

	private fun startStalledBufferRecovery() {
		if (stalledBufferRecoveryJob?.isActive == true) return

		val entry = manager.queue.entry.value ?: return
		if (entry.mediaStream?.identifier == null) return
		if (!entry.isLiveTvEntry()) return

		stalledBufferRecoveryJob = coroutineScope.launch(Dispatchers.Main) {
			try {
				var recoveryAttempts = 0
				var streamIdentifier: String? = null
				var lastPosition = state.positionInfo.active
				var lastBuffer = state.positionInfo.buffer

				while (state.playState.value == PlayState.BUFFERING) {
					val currentEntry = manager.queue.entry.value
					val currentStreamIdentifier = currentEntry?.mediaStream?.identifier
					if (currentEntry?.isLiveTvEntry() != true || currentStreamIdentifier == null) {
						return@launch
					}

					if (currentStreamIdentifier != streamIdentifier) {
						streamIdentifier = currentStreamIdentifier
						val positionInfo = state.positionInfo
						lastPosition = positionInfo.active
						lastBuffer = positionInfo.buffer
					}

					delay(stalledBufferRecoveryDelay(recoveryAttempts))

					val delayedEntry = manager.queue.entry.value
					val delayedStreamIdentifier = delayedEntry?.mediaStream?.identifier
					if (state.playState.value != PlayState.BUFFERING) return@launch
					if (delayedEntry?.isLiveTvEntry() != true || delayedStreamIdentifier == null) return@launch

					if (delayedStreamIdentifier != streamIdentifier) {
						streamIdentifier = delayedStreamIdentifier
						val positionInfo = state.positionInfo
						lastPosition = positionInfo.active
						lastBuffer = positionInfo.buffer
						continue
					}

					val positionInfo = state.positionInfo
					if (positionInfo.active > lastPosition || positionInfo.buffer > lastBuffer) {
						recoveryAttempts = 0
						lastPosition = positionInfo.active
						lastBuffer = positionInfo.buffer
						continue
					}

					if (recoveryAttempts >= MAX_STALLED_BUFFER_RECOVERY_ATTEMPTS) {
						Timber.w(
							"Live TV stalled-buffer recovery exhausted after %s attempts at position %s with buffer %s",
							recoveryAttempts,
							positionInfo.active,
							positionInfo.buffer,
						)
						return@launch
					}

					val stalledDuration = stalledBufferRecoveryDelay(recoveryAttempts)
					recoveryAttempts += 1
					val startMessage = "Live TV buffering stalled for $stalledDuration at position ${positionInfo.active} " +
						"with buffer ${positionInfo.buffer}; reloading current stream attempt " +
						"$recoveryAttempts/$MAX_STALLED_BUFFER_RECOVERY_ATTEMPTS"
					val reloaded = reloadCurrentLiveTvStream(
						startMessage = startMessage,
						successMessage = "Reloaded Live TV queue entry after stalled buffering",
						failureMessage = "Unable to reload Live TV queue entry after stalled buffering",
					)
					if (!reloaded) return@launch

					val reloadedPositionInfo = state.positionInfo
					lastPosition = reloadedPositionInfo.active
					lastBuffer = reloadedPositionInfo.buffer
				}
			} finally {
				stalledBufferRecoveryJob = null
			}
		}
	}

	private suspend fun reloadCurrentLiveTvStream(
		startMessage: String,
		successMessage: String,
		failureMessage: String,
	): Boolean {
		Timber.i(startMessage)
		val reloaded = manager.reloadCurrentMediaStream(position = null, playWhenReady = true)
		if (reloaded) {
			Timber.i(successMessage)
		} else {
			Timber.w(failureMessage)
		}
		return reloaded
	}

	private fun cancelStalledBufferRecovery() {
		stalledBufferRecoveryJob?.cancel()
		stalledBufferRecoveryJob = null
	}

	private fun QueueEntry.isLiveTvEntry(): Boolean =
		baseItem?.let(liveTvPlaybackPolicy::isLiveTv) == true

	private fun stalledBufferRecoveryDelay(recoveryAttempts: Int) =
		STALLED_BUFFER_RECOVERY_INTERVAL * (recoveryAttempts + 1)

	private companion object {
		private const val MAX_FORCE_TRANSCODING_RECOVERY_ATTEMPTS = 1
		private const val MAX_STALLED_BUFFER_RECOVERY_ATTEMPTS = 3
		private val STALLED_BUFFER_RECOVERY_INTERVAL = 15.seconds
	}
}
