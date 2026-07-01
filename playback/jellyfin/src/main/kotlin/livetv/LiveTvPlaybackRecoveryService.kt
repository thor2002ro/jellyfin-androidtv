package org.jellyfin.playback.jellyfin.livetv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.Queue
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
	private var playbackErrorRecoveryJob: Job? = null
	private var streamEndRecoveryJob: Job? = null
	private var stalledBufferRecoveryJob: Job? = null
	private var lastPlaybackError: PlaybackError? = null

	override suspend fun onInitialize() {
		manager.addBackendEventListener(object : PlayerBackendEventListener() {
			override fun onPlaybackError(error: PlaybackError) {
				val entry = manager.queue.entry.value
				lastPlaybackError = error.takeIf { entry?.isLiveTvEntry() == true }
			}

			override fun onMediaStreamEnd(mediaStream: PlayableMediaStream) {
				startStreamEndRecovery()
			}
		})

		state.playState.onEach { playState ->
			when (playState) {
				PlayState.ERROR -> {
					cancelStalledBufferRecovery()
					startPlaybackErrorRecovery()
				}

				PlayState.BUFFERING -> {
					lastPlaybackError = null
					cancelPlaybackErrorRecovery()
					startStalledBufferRecovery()
				}

				PlayState.PLAYING,
				PlayState.PAUSED -> {
					lastPlaybackError = null
					cancelPlaybackErrorRecovery()
					cancelStalledBufferRecovery()
				}

				PlayState.STOPPED -> {
					lastPlaybackError = null
					cancelStalledBufferRecovery()
				}
			}
		}.launchIn(coroutineScope)
	}

	private fun startPlaybackErrorRecovery() {
		if (playbackErrorRecoveryJob?.isActive == true) return

		playbackErrorRecoveryJob = coroutineScope.launch(Dispatchers.Main) {
			try {
				while (state.playState.value != PlayState.PLAYING && state.playState.value != PlayState.BUFFERING) {
					val entry = manager.queue.entry.value ?: return@launch
					if (!entry.isLiveTvEntry()) return@launch

					delay(PLAYBACK_ERROR_RETRY_INTERVAL)

					if (state.playState.value == PlayState.PLAYING || state.playState.value == PlayState.BUFFERING) return@launch
					val delayedEntry = manager.queue.entry.value ?: return@launch
					if (delayedEntry !== entry || !delayedEntry.isLiveTvEntry()) return@launch

					recoverPlaybackError(delayedEntry, lastPlaybackError)
				}
			} finally {
				playbackErrorRecoveryJob = null
			}
		}
	}

	private suspend fun recoverPlaybackError(entry: QueueEntry, playbackError: PlaybackError?) {
		if (playbackError?.recoverWithIncreasedLiveTvOffset == true) {
			val liveStreamTargetOffset = liveTvPlaybackPolicy.increaseLiveStreamTargetOffset(entry)
			reloadCurrentLiveTvStream(
				startMessage = "Reloading Live TV queue entry after ${playbackError.codeName}; target offset $liveStreamTargetOffset",
				successMessage = "Reloaded Live TV queue entry after ${playbackError.codeName}",
				failureMessage = "Unable to reload Live TV queue entry after ${playbackError.codeName}",
			)
			return
		}

		if (entry.forceTranscoding == true) {
			val attempts = entry.forceTranscodingRecoveryAttempts ?: 0
			if (attempts >= MAX_FORCE_TRANSCODING_RECOVERY_ATTEMPTS) {
				Timber.w("Live TV quality-forced transcoding recovery exhausted; retrying current transcode")
			} else {
				entry.forceTranscodingRecoveryAttempts = attempts + 1
			}

			reloadCurrentLiveTvStream(
				startMessage = "Reloading Live TV quality-forced transcode after playback error",
				successMessage = "Reloaded Live TV quality-forced transcode after playback error",
				failureMessage = "Unable to reload Live TV quality-forced transcode after playback error",
			)
			return
		}
		if (!entry.isLiveTvEntry()) return

		reloadCurrentLiveTvStream(
			startMessage = "Reloading Live TV queue entry after playback error without changing playback method",
			successMessage = "Reloaded Live TV queue entry after playback error",
			failureMessage = "Unable to reload Live TV queue entry after playback error",
		)
	}

	private fun startStreamEndRecovery() {
		if (streamEndRecoveryJob?.isActive == true) return

		val entry = manager.queue.entry.value ?: return
		if (!entry.isLiveTvEntry()) return

		val entryIndex = manager.queue.entryIndex.value
		if (entryIndex == Queue.INDEX_NONE) return

		streamEndRecoveryJob = coroutineScope.launch(Dispatchers.Main) {
			try {
				while (true) {
					delay(PLAYBACK_ERROR_RETRY_INTERVAL)

					if (manager.queue.entries.value.getOrNull(entryIndex) !== entry) return@launch

					val currentEntry = manager.queue.entry.value
					if (currentEntry != null && currentEntry !== entry) return@launch

					val retryEntry = currentEntry ?: entry
					if (!retryEntry.isLiveTvEntry()) return@launch

					retryEntry.mediaStream = null

					if (currentEntry == null) {
						Timber.i("Restarting Live TV queue entry after stream ended")
						manager.queue.setIndex(entryIndex)
					} else if (state.playState.value != PlayState.PLAYING && state.playState.value != PlayState.BUFFERING) {
						reloadCurrentLiveTvStream(
							startMessage = "Reloading Live TV queue entry after stream ended",
							successMessage = "Reloaded Live TV queue entry after stream ended",
							failureMessage = "Unable to reload Live TV queue entry after stream ended",
						)
					} else {
						return@launch
					}
				}
			} finally {
				streamEndRecoveryJob = null
			}
		}
	}

	private fun startStalledBufferRecovery() {
		if (stalledBufferRecoveryJob?.isActive == true) return

		val entry = manager.queue.entry.value ?: return
		if (entry.mediaStream?.identifier == null) return
		if (!entry.isLiveTvEntry()) return

		stalledBufferRecoveryJob = coroutineScope.launch(Dispatchers.Main) {
			try {
				var bufferedRecoveryAttempts = 0
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

					delay(stalledBufferRecoveryDelay(bufferedRecoveryAttempts))

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
						bufferedRecoveryAttempts = 0
						lastPosition = positionInfo.active
						lastBuffer = positionInfo.buffer
						continue
					}

					val hasBufferedDataAhead = positionInfo.buffer > positionInfo.active
					if (!hasBufferedDataAhead) {
						val reloaded = reloadCurrentLiveTvStream(
							startMessage = "Live TV buffering stalled without incoming data at position ${positionInfo.active}; " +
								"reloading current stream without changing target offset",
							successMessage = "Reloaded Live TV queue entry after stalled connection",
							failureMessage = "Unable to reload Live TV queue entry after stalled connection; retrying",
						)
						if (reloaded) {
							val reloadedPositionInfo = state.positionInfo
							lastPosition = reloadedPositionInfo.active
							lastBuffer = reloadedPositionInfo.buffer
						}
						continue
					}

					if (bufferedRecoveryAttempts >= MAX_STALLED_BUFFER_RECOVERY_ATTEMPTS) {
						Timber.w(
							"Live TV stalled-buffer recovery exhausted after %s attempts at position %s with buffer %s",
							bufferedRecoveryAttempts,
							positionInfo.active,
							positionInfo.buffer,
						)
						return@launch
					}

					val stalledDuration = stalledBufferRecoveryDelay(bufferedRecoveryAttempts)
					val liveStreamTargetOffset = liveTvPlaybackPolicy.increaseLiveStreamTargetOffset(delayedEntry)
					bufferedRecoveryAttempts += 1
					val startMessage = "Live TV buffering stalled for $stalledDuration at position ${positionInfo.active} " +
						"with buffer ${positionInfo.buffer}; target offset $liveStreamTargetOffset; reloading current stream attempt " +
						"$bufferedRecoveryAttempts/$MAX_STALLED_BUFFER_RECOVERY_ATTEMPTS"
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

	private fun cancelPlaybackErrorRecovery() {
		playbackErrorRecoveryJob?.cancel()
		playbackErrorRecoveryJob = null
	}

	private fun QueueEntry.isLiveTvEntry(): Boolean =
		baseItem?.let(liveTvPlaybackPolicy::isLiveTv) == true

	private fun stalledBufferRecoveryDelay(recoveryAttempts: Int) =
		STALLED_BUFFER_RECOVERY_INTERVAL * (recoveryAttempts + 1)

	private companion object {
		private const val MAX_FORCE_TRANSCODING_RECOVERY_ATTEMPTS = 1
		private const val MAX_STALLED_BUFFER_RECOVERY_ATTEMPTS = 3
		private val PLAYBACK_ERROR_RETRY_INTERVAL = 5.seconds
		private val STALLED_BUFFER_RECOVERY_INTERVAL = 15.seconds
	}
}
