package org.jellyfin.playback.jellyfin.recovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
import org.jellyfin.playback.dovi.doviDecision
import org.jellyfin.playback.jellyfin.livetv.LiveTvPlaybackPolicy
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class NetworkPlaybackRecoveryService(
	private val liveTvPlaybackPolicy: LiveTvPlaybackPolicy,
	private val networkAvailable: () -> Boolean = { true },
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
				if ((wasPaused || entry.hasTranscodeFallback) && savedRecoveryPosition(entry) != null) {
					startRecovery(entry, "Resuming interrupted playback", playWhenReady = true)
				}
			}

			override fun onSeekRequested(position: Duration) {
				val entry = manager.queue.entry.value ?: return
				if (!handles(entry) || isFatalRecoveryBlocked(entry)) return
				if (recoveringEntry !== entry && savedRecoveryPosition(entry) == null &&
					(pendingRecoveryStream == null || pendingRecoveryStream !== entry.mediaStream)
				) return
				recoveryPosition = NetworkRecoveryPosition(manager.queue.entryRevision, entry.mediaStream, position)
				recoveryOwnership?.takeIf { it.entry === entry }?.job?.cancel()
				startRecovery(entry, "Playback seek during recovery", playWhenReady = state.playWhenReady.value)
			}

			override fun onPlayStateChange(state: PlayState) {
				val entry = manager.queue.entry.value
				if (failedRecoveryStream !== entry?.mediaStream || state == PlayState.PLAYING) failedRecoveryStream = null
				if (state == PlayState.ERROR) failedRecoveryStream = entry?.mediaStream
				if (pendingRecoveryStream?.queueEntry !== entry || pendingRecoveryStream !== entry?.mediaStream) {
					pendingRecoveryStream = null
				}
				if (state == PlayState.PAUSED && !this@NetworkPlaybackRecoveryService.state.playWhenReady.value) {
					pauseRecovery()
				}
				if (state != PlayState.PLAYING || entry == null || !this@NetworkPlaybackRecoveryService.state.playWhenReady.value) return
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
				if (manager.queue.entry.value !== entry || !handles(entry)) return true
				if (failedRecoveryStream === mediaStream) return false
				val pendingReplacement = pendingRecoveryStream === mediaStream
				val position = state.positionInfo
				val duration = position.duration.takeIf { it > Duration.ZERO && it.isFinite() }
					?: entry.metadata.duration?.takeIf { it > Duration.ZERO && it.isFinite() }
				val owner = recoveryOwnership?.takeIf { it.entry === entry }
				val interrupted = pendingReplacement || recoveringEntry === entry && (
					errorRecoveryAttemptedEntry === entry || bufferingRecoveryAttemptedEntry === entry ||
						owner != null && mediaStream !== owner.sourceStream
				)
				// Allow final-frame rounding; without a duration, connectivity loss alone cannot disprove a native EOF.
				if (!liveTvPlaybackPolicy.isLiveTv(entry) && (
						duration != null && position.active >= duration - 1.seconds || duration == null && !interrupted
					)
				) {
					if (pendingReplacement) pendingRecoveryStream = null
					clearRecovering(entry)
					return true
				}
				if (pendingReplacement && failedRecoveryStream !== mediaStream) {
					manager.reportPlaybackError(PlaybackError("MEDIA_STREAM_RECOVERY_FAILED", origin = mediaStream.errorOrigin))
				}
				startRecovery(entry, "Playback ended before completion", playWhenReady = true)
				return false
			}

			override fun onPlaybackError(error: PlaybackError) {
				val entry = manager.queue.entry.value
				if (failedRecoveryStream !== entry?.mediaStream) failedRecoveryStream = null
				if (entry == null || error.origin?.matches(entry) != true) return
				// Capture the failed route before switching replaces the Dolby Vision decision.
				val previousDoviDecision = entry.doviDecision
				if (switchToTranscoding(entry, error)) {
					Timber.w(
						"Switching to server transcoding itemId=%s code=%s previousDvRoute=%s previousDvReason=%s",
						entry.metadata.mediaId, error.codeName, previousDoviDecision?.route, previousDoviDecision?.reason,
					)
					fatalRecoveryOrigin = null
					recoveryOwnership?.takeIf { it.entry === entry }?.job?.cancel()
				}
				if (!handles(entry)) return
				val transcodeFallback = entry.hasTranscodeFallback
				if (transcodeFallback && !state.playWhenReady.value) {
					recoveryPosition = NetworkRecoveryPosition(
						manager.queue.entryRevision, entry.mediaStream, entry.startPosition ?: state.positionInfo.active,
					)
					return
				}
				if (!transcodeFallback && !isOrdinaryRecoveryErrorEligible(error, entry)) {
					if (error.codeName != "MEDIA_STREAM_RECOVERY_FAILED" && error.origin?.matches(entry) == true) {
						Timber.w("Playback recovery blocked itemId=%s code=%s", entry.metadata.mediaId, error.codeName)
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
					playWhenReady = state.playWhenReady.value,
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
			var checksWithoutPlaybackProgress = 0
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
				val lastPlaybackPositionChanged = lastPositionInfo.active != positionInfo.active
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
					!handles(entry) ||
					!state.playWhenReady.value || pausedEntryRevision == manager.queue.entryRevision ||
					isFatalRecoveryBlocked(entry) ||
					playState == PlayState.STOPPED && recoveringEntry !== entry
				) {
					disconnectedEntry = null
					bufferingEntry = null
					consecutiveBufferingChecks = 0
					checksWithoutPlaybackProgress = 0
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
					checksWithoutPlaybackProgress = 0
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
						checksWithoutPlaybackProgress = 0
					}
					consecutiveBufferingChecks = if (bufferingProgressed) 0 else consecutiveBufferingChecks + 1
					checksWithoutPlaybackProgress = if (lastPlaybackPositionChanged) 0 else checksWithoutPlaybackProgress + 1
					if (
						shouldRecoverStalledBuffer(
							consecutiveChecks = consecutiveBufferingChecks,
							requiredChecks = BUFFERING_RECOVERY_CHECKS,
							hasPlayed = playedEntry === entry || entry.hasTranscodeFallback,
							checksWithoutPlaybackProgress = checksWithoutPlaybackProgress,
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
					checksWithoutPlaybackProgress = 0
				}

				if (!wasNetworkAvailable && disconnectedEntry === entry) {
					startRecovery(
						entry = entry,
						reason = "Network restored",
						playWhenReady = state.playWhenReady.value,
					)
					disconnectedEntry = null
				}

				wasNetworkAvailable = true
				lastPlayState = playState
			}
		}
	}

	private fun startRecovery(entry: QueueEntry, reason: String, playWhenReady: Boolean) {
		if (!playWhenReady || !state.playWhenReady.value || pausedEntryRevision == manager.queue.entryRevision || isFatalRecoveryBlocked(entry)) return
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
			} catch (error: CancellationException) {
				val outcome = when {
					manager.queue.entry.value !== entry || manager.queue.entryRevision != entryRevision -> "stopped or item changed"
					!state.playWhenReady.value -> "paused"
					isFatalRecoveryBlocked(entry) -> "unrecoverable playback error"
					else -> "superseded or service ended"
				}
				Timber.i("Playback recovery canceled itemId=%s outcome=%s", entry.metadata.mediaId, outcome)
				throw error
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
		val itemId = entry.metadata.mediaId
		var attempt = 0
		var loggedWaitingForNetwork = false
		var checksWithoutPlaybackProgress = 0
		var expectedStream = sourceStream
		val preparingEntry = manager.preparingEntry ?: return

		while (isCurrentRecoverableEntry(entry, entryRevision, expectedStream)) {
			if (!isNetworkAvailable()) {
				if (!loggedWaitingForNetwork) {
					Timber.i("Playback recovery waiting for network itemId=%s reason=%s", itemId, reason)
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
			if (playState != PlayState.PAUSED && hasPlaybackRecovered(playState, positionBeforeGrace.active, positionAfterGrace.active)) {
				Timber.i("Playback recovery finished itemId=%s outcome=playback progressing", itemId)
				recoveryPosition = null
				if (pendingRecoveryStream === expectedStream) pendingRecoveryStream = null
				return
			}
			checksWithoutPlaybackProgress = if (positionBeforeGrace.active != positionAfterGrace.active) 0 else checksWithoutPlaybackProgress + 1
			if (playState == PlayState.BUFFERING && hasBufferingProgress(positionBeforeGrace, positionAfterGrace) &&
				!shouldRecoverStalledBuffer(0, BUFFERING_RECOVERY_CHECKS, hasPlayed = false, checksWithoutPlaybackProgress = checksWithoutPlaybackProgress)
			) continue
			checksWithoutPlaybackProgress = 0

			attempt++
			Timber.i(
				"Playback recovery attempt itemId=%s attempt=%d reason=%s position=%s transcodeFallback=%s",
				itemId, attempt, reason, retryPosition, entry.hasTranscodeFallback,
			)
			val reloadResult = try {
				manager.reloadCurrentMediaStreamResult(
					position = retryPosition.takeUnless { liveTvPlaybackPolicy.isLiveTv(entry) },
					playWhenReady = playWhenReady,
				)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				Timber.w("Playback recovery request threw itemId=%s attempt=%d cause=%s", itemId, attempt, error.javaClass.simpleName)
				if (!entry.hasTranscodeFallback) throw error
				MediaStreamReloadResult.FAILED
			}
			if (reloadResult == MediaStreamReloadResult.RELOADED) {
				val replacement = entry.mediaStream ?: return
				expectedStream = replacement
				if (!isCurrentRecoverableEntry(entry, entryRevision, expectedStream)) return
				recoveryOwnership?.takeIf { it.job === currentCoroutineContext()[Job] }?.expectedStream = replacement
				if (state.playState.value != PlayState.PLAYING) recoveryPosition = NetworkRecoveryPosition(entryRevision, replacement, retryPosition)
				pendingRecoveryStream = replacement
				// The backend accepting a replacement does not mean playback has resumed.
				val result = withTimeoutOrNull(NETWORK_RECOVERY_CHECK_INTERVAL * BUFFERING_RECOVERY_CHECKS * 2) {
					combine(state.playState, manager.queue.entry, entry.mediaStreamFlow, preparingEntry) { playState, currentEntry, currentStream, preparing ->
						if (currentEntry !== entry || currentStream !== replacement || preparing === entry) PlayState.STOPPED else playState
					}.first { it != PlayState.BUFFERING }
				}
				val outcome = result?.name ?: "STARTUP_TIMEOUT"
				Timber.i("Playback recovery result itemId=%s attempt=%d outcome=%s", itemId, attempt, outcome)
				if (isCurrentRecoverableEntry(entry, entryRevision, expectedStream) && entry.mediaStream === replacement &&
					(result == null || result == PlayState.STOPPED) && failedRecoveryStream !== replacement
				) {
					manager.reportPlaybackError(PlaybackError("MEDIA_STREAM_RECOVERY_FAILED", origin = replacement.errorOrigin))
				}
			} else if (reloadResult == MediaStreamReloadResult.FAILED) {
				Timber.w("Playback recovery result itemId=%s attempt=%d outcome=RELOAD_FAILED", itemId, attempt)
				val playState = state.playState.value
				val stream = entry.mediaStream
				if (isCurrentRecoverableEntry(entry, entryRevision, expectedStream) && (playState == PlayState.STOPPED || playState == PlayState.BUFFERING) &&
					(stream == null || failedRecoveryStream !== stream)
				) {
					manager.reportPlaybackError(PlaybackError("MEDIA_STREAM_RECOVERY_FAILED", origin = stream?.errorOrigin))
				}
			}
			if (reloadResult == MediaStreamReloadResult.SUPERSEDED) {
				Timber.i("Playback recovery result itemId=%s attempt=%d outcome=SUPERSEDED", itemId, attempt)
				return
			}
			if (!state.playWhenReady.value || !playWhenReady) return
		}
	}

	private fun isCurrentRecoverableEntry(entry: QueueEntry, entryRevision: Long, expectedStream: PlayableMediaStream?): Boolean {
		val currentEntry = manager.queue.entry.value
		return currentEntry === entry &&
			manager.queue.entryRevision == entryRevision &&
			entry.mediaStream === expectedStream && manager.preparingEntry?.value !== entry &&
			state.playWhenReady.value && handles(entry) &&
			recoveringEntry === entry
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

	private fun handles(entry: QueueEntry) = !liveTvPlaybackPolicy.isLiveTv(entry) || entry.hasTranscodeFallback

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

internal fun shouldRecoverStalledBuffer(
	consecutiveChecks: Int,
	requiredChecks: Int,
	hasPlayed: Boolean,
	checksWithoutPlaybackProgress: Int = 0,
) = consecutiveChecks >= (if (hasPlayed) requiredChecks else requiredChecks * 2) ||
	checksWithoutPlaybackProgress >= requiredChecks * 2

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
	"MEDIA_STREAM_UNAVAILABLE",
	-> true
	else -> false
}

internal fun isOrdinaryRecoveryErrorEligible(error: PlaybackError, entry: QueueEntry): Boolean =
	isRecoverablePlaybackError(error.codeName) && error.origin?.matches(entry) == true
