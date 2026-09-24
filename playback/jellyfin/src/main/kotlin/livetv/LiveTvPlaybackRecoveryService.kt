package org.jellyfin.playback.jellyfin.livetv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.backend.matches
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.Queue
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.liveStreamTargetOffset
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.dovi.doviRecoveryOwnership
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.forceTranscoding
import org.jellyfin.playback.jellyfin.queue.forceTranscodingRecoveryAttempts
import org.jellyfin.playback.jellyfin.recovery.DoviRecoveryHandoffCoordinator
import org.jellyfin.playback.jellyfin.recovery.doviTransformationFailureStatus
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

class LiveTvPlaybackRecoveryService(
	private val liveTvPlaybackPolicy: LiveTvPlaybackPolicy,
	private val networkAvailable: () -> Boolean = { true },
	private val doviRecoveryHandoff: DoviRecoveryHandoffCoordinator? = null,
) : PlayerService() {
	private var playbackErrorRecoveryJob: Job? = null
	private var streamEndRecoveryJob: Job? = null
	private var stalledBufferRecoveryJob: Job? = null
	private var lastPlaybackError: PlaybackError? = null
	private var doviTransformationErrorEntry: QueueEntry? = null

	override suspend fun onInitialize() {
		doviRecoveryHandoff?.register(::cancelAndJoinGenericRecovery)
		manager.addBackendEventListener(object : PlayerBackendEventListener() {
			override fun onPlaybackError(error: PlaybackError) {
				val entry = manager.queue.entry.value
				if (shouldSuppressLiveTvGenericRecovery(error, entry)) {
					doviTransformationErrorEntry = entry
					lastPlaybackError = null
					return
				}
				if (error.origin?.matches(entry) != true) return
				if (entry?.doviRecoveryOwnership != null) return
				if (doviTransformationErrorEntry !== entry) doviTransformationErrorEntry = null
				lastPlaybackError = error.takeIf { entry?.let(liveTvPlaybackPolicy::isLiveTv) == true }
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
					if (manager.queue.entry.value?.doviRecoveryOwnership == null) startStalledBufferRecovery()
				}

				PlayState.PLAYING -> {
					if (manager.queue.entry.value?.doviRecoveryOwnership == null) {
						doviTransformationErrorEntry = null
					}
					manager.queue.entry.value?.let { entry ->
						resetLiveTvRecoveryAttempts(entry, liveTvPlaybackPolicy)
					}
					lastPlaybackError = null
					cancelPlaybackErrorRecovery()
					cancelStalledBufferRecovery()
				}

				PlayState.PAUSED -> {
					if (manager.queue.entry.value?.doviRecoveryOwnership == null) {
						doviTransformationErrorEntry = null
					}
					lastPlaybackError = null
					cancelPlaybackErrorRecovery()
					cancelStalledBufferRecovery()
				}

				PlayState.STOPPED -> {
					cancelStalledBufferRecovery()
					if (lastPlaybackError != null) {
						startPlaybackErrorRecovery()
					} else {
						cancelPlaybackErrorRecovery()
					}
				}
			}
		}.launchIn(coroutineScope)
	}

	private fun startPlaybackErrorRecovery() {
		val currentEntry = manager.queue.entry.value
		if (currentEntry?.doviRecoveryOwnership != null || doviTransformationErrorEntry === currentEntry) return
		if (playbackErrorRecoveryJob?.isActive == true) return
		streamEndRecoveryJob?.cancel()
		streamEndRecoveryJob = null

		playbackErrorRecoveryJob = coroutineScope.launch(Dispatchers.Main) {
			try {
				while (state.playState.value != PlayState.PLAYING && state.playState.value != PlayState.BUFFERING) {
					val entry = manager.queue.entry.value ?: return@launch
					if (entry.doviRecoveryOwnership != null || !liveTvPlaybackPolicy.isLiveTv(entry)) return@launch

					delay(PLAYBACK_ERROR_RETRY_INTERVAL)

					if (state.playState.value == PlayState.PLAYING || state.playState.value == PlayState.BUFFERING) return@launch
					val delayedEntry = manager.queue.entry.value ?: return@launch
					if (
						delayedEntry !== entry ||
						delayedEntry.doviRecoveryOwnership != null ||
						!liveTvPlaybackPolicy.isLiveTv(delayedEntry)
					) return@launch

					recoverPlaybackError(delayedEntry, lastPlaybackError)
				}
			} finally {
				if (playbackErrorRecoveryJob === coroutineContext[Job]) {
					playbackErrorRecoveryJob = null
				}
			}
		}
	}

	private suspend fun recoverPlaybackError(entry: QueueEntry, playbackError: PlaybackError?) {
		if (entry.doviRecoveryOwnership != null) return
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
		if (!liveTvPlaybackPolicy.isLiveTv(entry)) return

		reloadCurrentLiveTvStream(
			startMessage = "Reloading Live TV queue entry after playback error without changing playback method",
			successMessage = "Reloaded Live TV queue entry after playback error",
			failureMessage = "Unable to reload Live TV queue entry after playback error",
		)
	}

	private fun startStreamEndRecovery() {
		if (streamEndRecoveryJob?.isActive == true) return
		if (lastPlaybackError != null) return

		val entry = manager.queue.entry.value ?: return
		if (entry.doviRecoveryOwnership != null || doviTransformationErrorEntry === entry) return
		if (!liveTvPlaybackPolicy.isLiveTv(entry)) return

		val entryIndex = manager.queue.entryIndex.value
		if (entryIndex == Queue.INDEX_NONE) return

		streamEndRecoveryJob = coroutineScope.launch(Dispatchers.Main) {
			try {
				while (true) {
					delay(PLAYBACK_ERROR_RETRY_INTERVAL)

					if (entry.doviRecoveryOwnership != null) return@launch
					if (manager.queue.entries.value.getOrNull(entryIndex) !== entry) return@launch

					val currentEntry = manager.queue.entry.value
					if (currentEntry != null && currentEntry !== entry) return@launch

					val retryEntry = currentEntry ?: entry
					if (!liveTvPlaybackPolicy.isLiveTv(retryEntry)) return@launch

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
				if (streamEndRecoveryJob === coroutineContext[Job]) {
					streamEndRecoveryJob = null
				}
			}
		}
	}

	private fun startStalledBufferRecovery() {
		if (stalledBufferRecoveryJob?.isActive == true) return
		if (!manager.backend.reportsBufferedPosition) {
			Timber.i("Skipping Live TV stalled-buffer recovery; backend does not report buffered position")
			return
		}

		val entry = manager.queue.entry.value ?: return
		if (entry.doviRecoveryOwnership != null) return
		if (entry.mediaStream?.identifier == null) return
		if (!liveTvPlaybackPolicy.isLiveTv(entry)) return

		stalledBufferRecoveryJob = coroutineScope.launch(Dispatchers.Main) {
			try {
				var bufferedRecoveryAttempts = 0
				var streamIdentifier: String? = null
				var lastPosition = state.positionInfo.active
				var lastBuffer = state.positionInfo.buffer

				while (state.playState.value == PlayState.BUFFERING) {
					val currentEntry = manager.queue.entry.value
					if (currentEntry?.doviRecoveryOwnership != null) return@launch
					val currentStreamIdentifier = currentEntry?.mediaStream?.identifier
					if (currentEntry?.let(liveTvPlaybackPolicy::isLiveTv) != true || currentStreamIdentifier == null) {
						return@launch
					}

					if (currentStreamIdentifier != streamIdentifier) {
						streamIdentifier = currentStreamIdentifier
						val positionInfo = state.positionInfo
						lastPosition = positionInfo.active
						lastBuffer = positionInfo.buffer
					}

					delay(STALLED_BUFFER_RECOVERY_INTERVAL)

					val delayedEntry = manager.queue.entry.value
					if (delayedEntry?.doviRecoveryOwnership != null) return@launch
					val delayedStreamIdentifier = delayedEntry?.mediaStream?.identifier
					if (state.playState.value != PlayState.BUFFERING) return@launch
					if (delayedEntry?.let(liveTvPlaybackPolicy::isLiveTv) != true || delayedStreamIdentifier == null) return@launch

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

					val stalledDuration = STALLED_BUFFER_RECOVERY_INTERVAL
					bufferedRecoveryAttempts += 1
					if (!shouldReloadBufferedLiveTvStall(bufferedRecoveryAttempts, BUFFERED_STALL_RECOVERY_ATTEMPTS_BEFORE_RELOAD)) {
						Timber.i(
							"Live TV buffering stalled for $stalledDuration at position ${positionInfo.active} " +
								"with buffer ${positionInfo.buffer}; waiting before stream reload attempt " +
								"$bufferedRecoveryAttempts/$BUFFERED_STALL_RECOVERY_ATTEMPTS_BEFORE_RELOAD",
						)
						continue
					}

					val liveStreamTargetOffset = liveTvPlaybackPolicy.increaseLiveStreamTargetOffset(delayedEntry)
					val startMessage = "Live TV buffering stalled for $stalledDuration at position ${positionInfo.active} " +
						"with buffer ${positionInfo.buffer}; target offset $liveStreamTargetOffset; reloading current stream attempt " +
						"$bufferedRecoveryAttempts"
					val reloaded = reloadCurrentLiveTvStream(
						startMessage = startMessage,
						successMessage = "Reloaded Live TV queue entry after stalled buffering",
						failureMessage = "Unable to reload Live TV queue entry after stalled buffering",
					)
					if (!reloaded) continue

					val reloadedPositionInfo = state.positionInfo
					lastPosition = reloadedPositionInfo.active
					lastBuffer = reloadedPositionInfo.buffer
					bufferedRecoveryAttempts = 0
				}
			} finally {
				if (stalledBufferRecoveryJob === coroutineContext[Job]) {
					stalledBufferRecoveryJob = null
				}
			}
		}
	}

	private suspend fun reloadCurrentLiveTvStream(
		startMessage: String,
		successMessage: String,
		failureMessage: String,
	): Boolean {
		val entry = manager.queue.entry.value ?: return false
		if (entry.doviRecoveryOwnership != null) return false
		if (!liveTvPlaybackPolicy.isLiveTv(entry)) return false
		if (!waitForNetworkAvailable(entry, startMessage)) return false

		Timber.i(startMessage)
		val reloaded = manager.reloadCurrentMediaStream(position = null, playWhenReady = true)
		if (reloaded) {
			Timber.i(successMessage)
		} else {
			Timber.w(failureMessage)
		}
		return reloaded
	}

	private suspend fun waitForNetworkAvailable(entry: QueueEntry, reason: String): Boolean {
		var logged = false
		while (!isNetworkAvailable()) {
			val currentEntry = manager.queue.entry.value
			if (
				currentEntry !== entry ||
				entry.doviRecoveryOwnership != null ||
				!liveTvPlaybackPolicy.isLiveTv(currentEntry) ||
				state.playState.value == PlayState.STOPPED
			) {
				return false
			}

			if (!logged) {
				Timber.i("Network unavailable; waiting before $reason")
				logged = true
			}
			delay(PLAYBACK_ERROR_RETRY_INTERVAL)
		}

		return true
	}

	private fun isNetworkAvailable(): Boolean =
		runCatching(networkAvailable).getOrElse { error ->
			Timber.w(error, "Unable to read network state; assuming network is available")
			true
		}

	private fun cancelStalledBufferRecovery() {
		stalledBufferRecoveryJob?.cancel()
		stalledBufferRecoveryJob = null
	}

	private fun cancelPlaybackErrorRecovery() {
		playbackErrorRecoveryJob?.cancel()
		playbackErrorRecoveryJob = null
	}

	private suspend fun cancelAndJoinGenericRecovery(entry: QueueEntry) {
		if (manager.queue.entry.value !== entry) return
		val currentJob = currentCoroutineContext()[Job]
		val playback = playbackErrorRecoveryJob
		val stalled = stalledBufferRecoveryJob
		val ended = streamEndRecoveryJob
		cancelAndJoinLiveTvRecoveryJobs(listOfNotNull(playback, stalled, ended), currentJob)
		if (playbackErrorRecoveryJob === playback) playbackErrorRecoveryJob = null
		if (stalledBufferRecoveryJob === stalled) stalledBufferRecoveryJob = null
		if (streamEndRecoveryJob === ended) streamEndRecoveryJob = null
	}

	private companion object {
		private const val MAX_FORCE_TRANSCODING_RECOVERY_ATTEMPTS = 1
		private const val BUFFERED_STALL_RECOVERY_ATTEMPTS_BEFORE_RELOAD = 3
		private val PLAYBACK_ERROR_RETRY_INTERVAL = 3.seconds
		private val STALLED_BUFFER_RECOVERY_INTERVAL = 3.seconds
	}
}

internal fun shouldReloadBufferedLiveTvStall(
	bufferedRecoveryAttempts: Int,
	attemptsBeforeReload: Int,
) = bufferedRecoveryAttempts >= attemptsBeforeReload

internal fun shouldSuppressLiveTvGenericRecovery(error: PlaybackError, currentEntry: QueueEntry?): Boolean =
	doviTransformationFailureStatus(error.codeName) != null &&
		error.origin?.matches(currentEntry) == true

internal suspend fun cancelAndJoinLiveTvRecoveryJobs(jobs: Collection<Job>, currentJob: Job?) {
	jobs.distinct().forEach { job ->
		if (job !== currentJob) job.cancelAndJoin()
	}
}

internal fun resetLiveTvRecoveryAttempts(
	entry: QueueEntry,
	liveTvPlaybackPolicy: LiveTvPlaybackPolicy,
) {
	if (!liveTvPlaybackPolicy.isLiveTv(entry)) return

	entry.liveStreamTargetOffset = LiveTvPlaybackPolicy.INITIAL_LIVE_STREAM_TARGET_OFFSET
	entry.forceTranscodingRecoveryAttempts = null
}
