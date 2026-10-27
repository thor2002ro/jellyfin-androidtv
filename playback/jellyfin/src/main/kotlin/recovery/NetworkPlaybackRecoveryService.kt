package org.jellyfin.playback.jellyfin.recovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.PlaybackErrorOrigin
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.backend.matches
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.MediaStreamReloadResult
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.mediaStreamFlow
import org.jellyfin.playback.core.mediastream.startPosition
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.model.isActivePlayback
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.metadata
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.dovi.doviRecoveryOwnership
import org.jellyfin.playback.jellyfin.livetv.LiveTvPlaybackPolicy
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class NetworkPlaybackRecoveryService(
	private val liveTvPlaybackPolicy: LiveTvPlaybackPolicy,
	private val networkAvailable: () -> Boolean = { true },
	private val doviRecoveryHandoff: DoviRecoveryHandoffCoordinator? = null,
) : PlayerService() {
	private var recoveryOwnership: NetworkRecoveryJobOwnership? = null
	private var recoveringEntry: QueueEntry? = null
	// PAUSED can precede input readiness; only PLAYING releases this stream's EOF guard.
	private var pendingRecoveryStream: PlayableMediaStream? = null
	private var failedRecoveryStream: PlayableMediaStream? = null
	private var fatalRecoveryOrigin: PlaybackErrorOrigin? = null
	private var fatalRecoveryEntryRevision = -1L
	private var pausedEntryRevision: Long? = null
	private var recoveryPosition: NetworkRecoveryPosition? = null
	private var errorRecoveryAttemptedEntry: QueueEntry? = null
	private var bufferingRecoveryAttemptedEntry: QueueEntry? = null
	private val _recovering = MutableStateFlow(false)
	val recovering: StateFlow<Boolean> = _recovering.asStateFlow()

	override suspend fun onInitialize() {
		doviRecoveryHandoff?.register(::cancelAndJoinOwnedRecovery)
		manager.addBackendEventListener(object : PlayerBackendEventListener() {
			override fun onPlayWhenReadyRequested(playWhenReady: Boolean) {
				if (!playWhenReady) {
					pauseRecovery()
					return
				}
				val wasPaused = pausedEntryRevision == manager.queue.entryRevision
				pausedEntryRevision = null
				fatalRecoveryOrigin = null
				val entry = manager.queue.entry.value ?: return
				if (wasPaused && savedRecoveryPosition(entry) != null) {
					startRecovery(entry, "Resuming interrupted playback", playWhenReady = true)
				}
			}

			override fun onSeekRequested(position: Duration) {
				val entry = manager.queue.entry.value ?: return
				if (liveTvPlaybackPolicy.isLiveTv(entry) || entry.doviRecoveryOwnership != null || isFatalRecoveryBlocked(entry)) return
				if (recoveringEntry !== entry && savedRecoveryPosition(entry) == null &&
					(pendingRecoveryStream == null || pendingRecoveryStream !== entry.mediaStream)
				) return
				recoveryPosition = NetworkRecoveryPosition(manager.queue.entryRevision, entry.mediaStream, position)
				recoveryOwnership?.takeIf { it.entry === entry }?.job?.cancel()
				startRecovery(entry, "Playback seek during recovery", playWhenReady = state.playState.value != PlayState.PAUSED)
			}

			override fun onPlayStateChange(state: PlayState) {
				val entry = manager.queue.entry.value
				if (failedRecoveryStream !== entry?.mediaStream || state == PlayState.PLAYING) failedRecoveryStream = null
				if (state == PlayState.ERROR) failedRecoveryStream = entry?.mediaStream
				if (pendingRecoveryStream?.queueEntry !== entry || pendingRecoveryStream !== entry?.mediaStream) {
					pendingRecoveryStream = null
				}
				if (state == PlayState.PAUSED) {
					pauseRecovery()
				}
				if (state != PlayState.PLAYING || entry == null) return
				pausedEntryRevision = null
				fatalRecoveryOrigin = null
				recoveryPosition = null
				val stream = entry.mediaStream ?: return
				if (pendingRecoveryStream === stream) pendingRecoveryStream = null
				val owner = recoveryOwnership
				if (owner != null && owner.entry === entry && stream !== owner.sourceStream) clearRecovering(entry)
			}

			override fun shouldHandleMediaStreamEnd(mediaStream: PlayableMediaStream): Boolean {
				val entry = mediaStream.queueEntry
				if (manager.queue.entry.value !== entry || liveTvPlaybackPolicy.isLiveTv(entry) || entry.doviRecoveryOwnership != null) return true
				if (failedRecoveryStream === mediaStream) return false
				val pendingReplacement = pendingRecoveryStream === mediaStream
				if (recoveringEntry !== entry && !pendingReplacement) return true
				val position = state.positionInfo
				val duration = position.duration.takeIf { it > Duration.ZERO && it.isFinite() }
					?: entry.metadata.duration?.takeIf { it > Duration.ZERO && it.isFinite() }
				val owner = recoveryOwnership?.takeIf { it.entry === entry }
				val interrupted = pendingReplacement || errorRecoveryAttemptedEntry === entry ||
					bufferingRecoveryAttemptedEntry === entry || owner != null && mediaStream !== owner.sourceStream
				// Allow final-frame rounding; without a duration, connectivity loss alone cannot disprove a native EOF.
				if (duration != null && position.active >= duration - 1.seconds || duration == null && !interrupted) {
					if (pendingReplacement) pendingRecoveryStream = null
					clearRecovering(entry)
					return true
				}
				if (pendingReplacement && failedRecoveryStream !== mediaStream) {
					manager.reportPlaybackError(PlaybackError("MEDIA_STREAM_RECOVERY_FAILED", origin = mediaStream.errorOrigin))
				}
				return false
			}

			override fun onPlaybackError(error: PlaybackError) {
				val entry = manager.queue.entry.value
				if (failedRecoveryStream !== entry?.mediaStream) failedRecoveryStream = null
				if (doviTransformationFailureStatus(error.codeName) != null) {
					return
				}
				if (
					entry == null ||
					entry.doviRecoveryOwnership != null ||
					liveTvPlaybackPolicy.isLiveTv(entry)
				) return
				if (!isOrdinaryRecoveryErrorEligible(error, entry)) {
					if (error.codeName != "MEDIA_STREAM_RECOVERY_FAILED" && error.origin?.matches(entry) == true) {
						fatalRecoveryOrigin = error.origin
						fatalRecoveryEntryRevision = manager.queue.entryRevision
						recoveryOwnership?.takeIf { it.entry === entry }?.job?.cancel()
						recoveryPosition = null
						clearRecovering(entry)
					}
					return
				}

				errorRecoveryAttemptedEntry = entry
				startRecovery(
					entry = entry,
					reason = "Playback error ${error.codeName}",
					playWhenReady = state.playState.value != PlayState.PAUSED,
				)
			}
		})
		coroutineScope.launch(Dispatchers.Main) {
			manager.queue.entry.collect { entry ->
				val owner = recoveryOwnership
				if (recoveryPosition?.entryRevision != manager.queue.entryRevision) recoveryPosition = null
				if (owner != null && (owner.entry !== entry || owner.entryRevision != manager.queue.entryRevision)) owner.job.cancel()
				if (recoveringEntry !== entry) clearRecovering()
			}
		}

		coroutineScope.launch(Dispatchers.Main) {
			var disconnectedEntry: QueueEntry? = null
			var wasNetworkAvailable = isNetworkAvailable()
			var lastPlayState = state.playState.value
			var bufferingEntry: QueueEntry? = null
			var consecutiveBufferingChecks = 0
			var playedEntry: QueueEntry? = null
			var lastPositionInfo = state.positionInfo

			while (true) {
				delay(NETWORK_RECOVERY_CHECK_INTERVAL)

				val entry = manager.queue.entry.value
				val owner = recoveryOwnership
				if (owner != null && (owner.entry !== entry || owner.entryRevision != manager.queue.entryRevision)) owner.job.cancel()
				if (pendingRecoveryStream?.queueEntry !== entry || pendingRecoveryStream !== entry?.mediaStream) {
					pendingRecoveryStream = null
				}
				val playState = state.playState.value
				val positionInfo = state.positionInfo
				val bufferingProgressed = hasBufferingProgress(lastPositionInfo, positionInfo)
				lastPositionInfo = positionInfo
				if (playState == PlayState.PLAYING) playedEntry = entry
				if (
					entry !== errorRecoveryAttemptedEntry ||
					recoveryOwnership?.job?.isActive != true &&
					(playState == PlayState.PLAYING || playState == PlayState.PAUSED)
				) {
					errorRecoveryAttemptedEntry = null
				}
				if (
					entry !== bufferingRecoveryAttemptedEntry ||
					recoveryOwnership?.job?.isActive != true &&
					(playState == PlayState.PLAYING || playState == PlayState.PAUSED)
				) {
					bufferingRecoveryAttemptedEntry = null
				}
				if (
					entry == null ||
					entry.doviRecoveryOwnership != null ||
					liveTvPlaybackPolicy.isLiveTv(entry) ||
					pausedEntryRevision == manager.queue.entryRevision ||
					isFatalRecoveryBlocked(entry) ||
					playState == PlayState.STOPPED && recoveringEntry !== entry
				) {
					disconnectedEntry = null
					bufferingEntry = null
					consecutiveBufferingChecks = 0
					bufferingRecoveryAttemptedEntry = null
					playedEntry = null
					clearRecovering()
					wasNetworkAvailable = isNetworkAvailable()
					lastPlayState = playState
					continue
				}

				val networkAvailable = isNetworkAvailable()
				if (!networkAvailable) {
					bufferingEntry = null
					consecutiveBufferingChecks = 0
					if (disconnectedEntry == null && (playState.isActivePlayback || lastPlayState.isActivePlayback || playState == PlayState.ERROR)) {
						Timber.w("Network unavailable during playback; waiting for connectivity to return")
						disconnectedEntry = entry
						setRecovering(entry)
					}
					wasNetworkAvailable = false
					lastPlayState = playState
					continue
				}

				if (playState == PlayState.BUFFERING) {
					if (bufferingEntry !== entry) {
						bufferingEntry = entry
						consecutiveBufferingChecks = 0
					}
					consecutiveBufferingChecks = if (bufferingProgressed) 0 else consecutiveBufferingChecks + 1
					if (
						shouldRecoverStalledBuffer(
							consecutiveChecks = consecutiveBufferingChecks,
							requiredChecks = BUFFERING_RECOVERY_CHECKS,
							hasPlayed = playedEntry === entry,
						) &&
						bufferingRecoveryAttemptedEntry !== entry
					) {
						bufferingRecoveryAttemptedEntry = entry
						startRecovery(
							entry = entry,
							reason = "Playback buffering stalled",
							playWhenReady = true,
						)
					}
				} else {
					bufferingEntry = null
					consecutiveBufferingChecks = 0
				}

				if (!wasNetworkAvailable && disconnectedEntry === entry) {
					startRecovery(
						entry = entry,
						reason = "Network restored",
						playWhenReady = playState != PlayState.PAUSED,
					)
					disconnectedEntry = null
				}

				wasNetworkAvailable = true
				lastPlayState = playState
			}
		}
	}

	private fun startRecovery(entry: QueueEntry, reason: String, playWhenReady: Boolean) {
		if (!playWhenReady || pausedEntryRevision == manager.queue.entryRevision || isFatalRecoveryBlocked(entry) || entry.doviRecoveryOwnership != null) return
		val entryRevision = manager.queue.entryRevision
		val sourceStream = entry.mediaStream
		val previous = recoveryOwnership
		if (previous?.job?.isActive == true) {
			if (previous.entry === entry && previous.entryRevision == entryRevision && previous.expectedStream === sourceStream) {
				recoveryPosition = NetworkRecoveryPosition(entryRevision, sourceStream, previous.retryPosition)
				setRecovering(entry)
				return
			}
			previous.job.cancel()
		}
		val retryPosition = savedRecoveryPosition(entry)?.position
			?: entry.startPosition ?: state.positionInfo.active
		recoveryPosition = NetworkRecoveryPosition(entryRevision, sourceStream, retryPosition)
		setRecovering(entry)

		val token = Any()
		lateinit var job: Job
		job = coroutineScope.launch(Dispatchers.Main, start = CoroutineStart.LAZY) {
			try {
				// Drain canceled requests even if another seek cancels this waiting worker.
				withContext(NonCancellable) { previous?.job?.join() }
				currentCoroutineContext().ensureActive()
				recoverEntry(entry, entryRevision, sourceStream, retryPosition, reason, playWhenReady)
			} finally {
				val owner = recoveryOwnership
				if (owner?.token === token && owner.job === coroutineContext[Job]) {
					recoveryOwnership = clearFinishedNetworkRecoveryOwnership(recoveryOwnership, token)
					clearRecovering(entry)
				}
			}
		}
		recoveryOwnership = NetworkRecoveryJobOwnership(entry, token, job, sourceStream, entryRevision, retryPosition)
		job.start()
	}

	private suspend fun recoverEntry(entry: QueueEntry, entryRevision: Long, sourceStream: PlayableMediaStream?, retryPosition: Duration, reason: String, playWhenReady: Boolean) {
		var loggedWaitingForNetwork = false
		var expectedStream = sourceStream
		val preparingEntry = manager.preparingEntry ?: return

		while (isCurrentRecoverableEntry(entry, entryRevision, expectedStream)) {
			if (!isNetworkAvailable()) {
				if (!loggedWaitingForNetwork) {
					Timber.i("$reason; waiting for network before reloading playback")
					loggedWaitingForNetwork = true
				}
				delay(NETWORK_RECOVERY_CHECK_INTERVAL)
				continue
			}

			val positionBeforeGrace = state.positionInfo
			delay(PLAYBACK_RECOVERY_RETRY_INTERVAL)
			if (!isCurrentRecoverableEntry(entry, entryRevision, expectedStream)) return
			if (!isNetworkAvailable()) continue

			val playState = state.playState.value
			val positionAfterGrace = state.positionInfo
			if (hasPlaybackRecovered(playState, positionBeforeGrace.active, positionAfterGrace.active)) {
				Timber.i("Playback progressed without reloading")
				recoveryPosition = null
				if (pendingRecoveryStream === expectedStream) pendingRecoveryStream = null
				return
			}
			if (playState == PlayState.BUFFERING && hasBufferingProgress(positionBeforeGrace, positionAfterGrace)) continue

			Timber.i("$reason; reloading playback at $retryPosition")
			val reloadResult = manager.reloadCurrentMediaStreamResult(position = retryPosition, playWhenReady = playWhenReady)
			if (reloadResult == MediaStreamReloadResult.RELOADED) {
				val replacement = entry.mediaStream ?: return
				expectedStream = replacement
				if (!isCurrentRecoverableEntry(entry, entryRevision, expectedStream)) return
				recoveryOwnership?.takeIf { it.job === currentCoroutineContext()[Job] }?.expectedStream = replacement
				if (state.playState.value != PlayState.PLAYING) recoveryPosition = NetworkRecoveryPosition(entryRevision, replacement, retryPosition)
				pendingRecoveryStream = replacement
				val result = withTimeoutOrNull(NETWORK_RECOVERY_CHECK_INTERVAL * BUFFERING_RECOVERY_CHECKS * 2) {
					combine(state.playState, manager.queue.entry, entry.mediaStreamFlow, preparingEntry) { playState, currentEntry, currentStream, preparing ->
						if (currentEntry !== entry || currentStream !== replacement || preparing === entry) PlayState.STOPPED else playState
					}.first { it != PlayState.BUFFERING }
				}
				if (isCurrentRecoverableEntry(entry, entryRevision, expectedStream) && entry.mediaStream === replacement &&
					(result == null || result == PlayState.STOPPED) && failedRecoveryStream !== replacement
				) {
					manager.reportPlaybackError(PlaybackError("MEDIA_STREAM_RECOVERY_FAILED", origin = replacement.errorOrigin))
				}
			} else if (reloadResult == MediaStreamReloadResult.FAILED) {
				Timber.w("Unable to reload playback after error")
				val playState = state.playState.value
				val stream = entry.mediaStream
				if (isCurrentRecoverableEntry(entry, entryRevision, expectedStream) && (playState == PlayState.STOPPED || playState == PlayState.BUFFERING) &&
					(stream == null || failedRecoveryStream !== stream)
				) {
					manager.reportPlaybackError(PlaybackError("MEDIA_STREAM_RECOVERY_FAILED", origin = stream?.errorOrigin))
				}
			}
			if (reloadResult == MediaStreamReloadResult.SUPERSEDED || state.playState.value == PlayState.PAUSED || !playWhenReady) return
		}
	}

	private fun isCurrentRecoverableEntry(entry: QueueEntry, entryRevision: Long, expectedStream: PlayableMediaStream?): Boolean {
		val currentEntry = manager.queue.entry.value
		return currentEntry === entry &&
			manager.queue.entryRevision == entryRevision &&
			entry.mediaStream === expectedStream && manager.preparingEntry?.value !== entry &&
			entry.doviRecoveryOwnership == null &&
			!liveTvPlaybackPolicy.isLiveTv(entry) &&
			recoveringEntry === entry
	}

	private suspend fun cancelAndJoinOwnedRecovery(entry: QueueEntry) {
		val owner = recoveryOwnership?.takeIf { it.entry === entry } ?: return
		if (!cancelAndJoinRecoveryJob(owner.job, currentCoroutineContext()[Job])) return
		if (recoveryOwnership?.token === owner.token) {
			recoveryOwnership = null
			clearRecovering(entry)
		}
	}

	private fun pauseRecovery() {
		val entry = manager.queue.entry.value
		pausedEntryRevision = manager.queue.entryRevision
		if (entry != null && recoveringEntry === entry) {
			pendingRecoveryStream = entry.mediaStream
			recoveryPosition = savedRecoveryPosition(entry)
				?: NetworkRecoveryPosition(manager.queue.entryRevision, entry.mediaStream, entry.startPosition ?: state.positionInfo.active)
		}
		recoveryOwnership?.takeIf { it.entry === entry }?.job?.cancel()
		clearRecovering(entry)
	}

	private fun savedRecoveryPosition(entry: QueueEntry) = recoveryPosition?.takeIf {
		it.entryRevision == manager.queue.entryRevision && it.stream === entry.mediaStream
	}

	private fun isFatalRecoveryBlocked(entry: QueueEntry) =
		fatalRecoveryEntryRevision == manager.queue.entryRevision && fatalRecoveryOrigin?.matches(entry) == true

	private fun isNetworkAvailable(): Boolean {
		return runCatching(networkAvailable).getOrElse { error ->
			Timber.w(error, "Unable to read network state; assuming network is available")
			true
		}
	}

	private fun setRecovering(entry: QueueEntry) {
		recoveringEntry = entry
		_recovering.value = true
	}

	private fun clearRecovering(entry: QueueEntry? = null) {
		if (entry != null && recoveringEntry !== entry) return
		recoveringEntry = null
		_recovering.value = false
	}

	private companion object {
		private val NETWORK_RECOVERY_CHECK_INTERVAL = 3.seconds
		private val PLAYBACK_RECOVERY_RETRY_INTERVAL = 3.seconds
		private const val BUFFERING_RECOVERY_CHECKS = 5
	}
}

private data class NetworkRecoveryPosition(val entryRevision: Long, val stream: PlayableMediaStream?, val position: Duration)

internal data class NetworkRecoveryJobOwnership(
	val entry: QueueEntry,
	val token: Any,
	val job: Job,
	val sourceStream: PlayableMediaStream? = entry.mediaStream,
	val entryRevision: Long = 0L,
	val retryPosition: Duration = Duration.ZERO,
	var expectedStream: PlayableMediaStream? = sourceStream,
)

internal fun clearFinishedNetworkRecoveryOwnership(
	current: NetworkRecoveryJobOwnership?,
	finishedToken: Any,
): NetworkRecoveryJobOwnership? = current.takeUnless { it?.token === finishedToken }

internal suspend fun cancelAndJoinRecoveryJob(job: Job, currentJob: Job?): Boolean {
	if (job === currentJob) return false
	job.cancelAndJoin()
	return true
}

// A changed playback position also covers seeking; buffer growth is healthy loading.
internal fun hasBufferingProgress(before: PositionInfo, after: PositionInfo) =
	after.active != before.active || after.buffer > before.buffer

internal fun shouldRecoverStalledBuffer(consecutiveChecks: Int, requiredChecks: Int, hasPlayed: Boolean) =
	consecutiveChecks >= if (hasPlayed) requiredChecks else requiredChecks * 2

internal fun hasPlaybackRecovered(
	playState: PlayState,
	positionBeforeGrace: Duration,
	positionAfterGrace: Duration,
) = playState == PlayState.PAUSED ||
	playState == PlayState.PLAYING && positionAfterGrace > positionBeforeGrace

internal fun isRecoverablePlaybackError(codeName: String) = doviTransformationFailureStatus(codeName) == null && when (codeName) {
	"ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
	"ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
	"ERROR_CODE_IO_BAD_HTTP_STATUS",
	"MPV_HTTP_ERROR",
	"MPV_ERROR",
	"LIBVLC_ERROR",
	"MEDIA_STREAM_NETWORK_ERROR",
	-> true
	else -> false
}

internal fun isOrdinaryRecoveryErrorEligible(error: PlaybackError, entry: QueueEntry): Boolean =
	isRecoverablePlaybackError(error.codeName) && error.origin?.matches(entry) == true
