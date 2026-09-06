package org.jellyfin.playback.jellyfin.recovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.backend.matches
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.isActivePlayback
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
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
	private var errorRecoveryAttemptedEntry: QueueEntry? = null
	private var bufferingRecoveryAttemptedEntry: QueueEntry? = null
	private val _recovering = MutableStateFlow(false)
	val recovering: StateFlow<Boolean> = _recovering.asStateFlow()

	override suspend fun onInitialize() {
		doviRecoveryHandoff?.register(::cancelAndJoinOwnedRecovery)
		manager.addBackendEventListener(object : PlayerBackendEventListener() {
			override fun onPlaybackError(error: PlaybackError) {
				val entry = manager.queue.entry.value
				if (doviTransformationFailureStatus(error.codeName) != null) {
					return
				}
				if (
					entry == null ||
					entry.doviRecoveryOwnership != null ||
					liveTvPlaybackPolicy.isLiveTv(entry) ||
					!isOrdinaryRecoveryErrorEligible(error, entry)
				) return
				if (errorRecoveryAttemptedEntry === entry) {
					Timber.w("Automatic recovery already attempted for ${error.codeName}")
					return
				}

				errorRecoveryAttemptedEntry = entry
				startRecovery(
					entry = entry,
					reason = "Playback error ${error.codeName}",
					playWhenReady = state.playState.value.isActivePlayback,
				)
			}
		})

		coroutineScope.launch(Dispatchers.Main) {
			var disconnectedEntry: QueueEntry? = null
			var wasNetworkAvailable = isNetworkAvailable()
			var lastPlayState = state.playState.value
			var bufferingEntry: QueueEntry? = null
			var consecutiveBufferingChecks = 0
			var playedEntry: QueueEntry? = null

			while (true) {
				delay(NETWORK_RECOVERY_CHECK_INTERVAL)

				val entry = manager.queue.entry.value
				val playState = state.playState.value
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
					playState == PlayState.STOPPED
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
					consecutiveBufferingChecks++
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
						playWhenReady = playState.isActivePlayback || playState == PlayState.ERROR,
					)
					disconnectedEntry = null
				}

				wasNetworkAvailable = true
				lastPlayState = playState
			}
		}
	}

	private fun startRecovery(entry: QueueEntry, reason: String, playWhenReady: Boolean) {
		if (entry.doviRecoveryOwnership != null) return
		val previous = recoveryOwnership
		if (previous?.job?.isActive == true) {
			if (previous.entry === entry) return
			previous.job.cancel()
		}
		setRecovering(entry)

		val token = Any()
		lateinit var job: Job
		job = coroutineScope.launch(Dispatchers.Main, start = CoroutineStart.LAZY) {
			try {
				recoverEntry(entry, reason, playWhenReady)
			} finally {
				val owner = recoveryOwnership
				if (owner?.token === token && owner.job === coroutineContext[Job]) {
					recoveryOwnership = clearFinishedNetworkRecoveryOwnership(recoveryOwnership, token)
					clearRecovering(entry)
				}
			}
		}
		recoveryOwnership = NetworkRecoveryJobOwnership(entry, token, job)
		job.start()
	}

	private suspend fun recoverEntry(entry: QueueEntry, reason: String, playWhenReady: Boolean) {
		var loggedWaitingForNetwork = false

		while (isCurrentRecoverableEntry(entry)) {
			if (!isNetworkAvailable()) {
				if (!loggedWaitingForNetwork) {
					Timber.i("$reason; waiting for network before reloading playback")
					loggedWaitingForNetwork = true
				}
				delay(NETWORK_RECOVERY_CHECK_INTERVAL)
				continue
			}

			val positionBeforeGrace = state.positionInfo.active
			delay(PLAYBACK_RECOVERY_RETRY_INTERVAL)
			if (!isCurrentRecoverableEntry(entry)) return
			if (!isNetworkAvailable()) continue

			val playState = state.playState.value
			val positionAfterGrace = state.positionInfo.active
			if (hasPlaybackRecovered(playState, positionBeforeGrace, positionAfterGrace)) {
				Timber.i("Playback recovered without reloading")
				return
			}

			Timber.i("$reason; reloading playback at $positionAfterGrace")
			if (manager.reloadCurrentMediaStream(position = positionAfterGrace, playWhenReady = playWhenReady)) {
				Timber.i("Reloaded playback after error")
			} else {
				Timber.w("Unable to reload playback after error")
			}
			return
		}
	}

	private fun isCurrentRecoverableEntry(entry: QueueEntry): Boolean {
		val currentEntry = manager.queue.entry.value
		return currentEntry === entry &&
			entry.doviRecoveryOwnership == null &&
			!liveTvPlaybackPolicy.isLiveTv(entry) &&
			state.playState.value != PlayState.STOPPED
	}

	private suspend fun cancelAndJoinOwnedRecovery(entry: QueueEntry) {
		val owner = recoveryOwnership?.takeIf { it.entry === entry } ?: return
		if (!cancelAndJoinRecoveryJob(owner.job, currentCoroutineContext()[Job])) return
		if (recoveryOwnership?.token === owner.token) {
			recoveryOwnership = null
			clearRecovering(entry)
		}
	}

	private fun isNetworkAvailable(): Boolean {
		return runCatching(networkAvailable).getOrElse { error ->
			Timber.w(error, "Unable to read network state; assuming network is available")
			true
		}
	}

	private fun setRecovering(entry: QueueEntry) {
		_recovering.value = true
	}

	private fun clearRecovering(entry: QueueEntry? = null) {
		if (entry != null && recoveryOwnership?.entry?.let { it !== entry } == true) return
		_recovering.value = false
	}

	private companion object {
		private val NETWORK_RECOVERY_CHECK_INTERVAL = 3.seconds
		private val PLAYBACK_RECOVERY_RETRY_INTERVAL = 3.seconds
		private const val BUFFERING_RECOVERY_CHECKS = 5
	}
}

internal data class NetworkRecoveryJobOwnership(val entry: QueueEntry, val token: Any, val job: Job)

internal fun clearFinishedNetworkRecoveryOwnership(
	current: NetworkRecoveryJobOwnership?,
	finishedToken: Any,
): NetworkRecoveryJobOwnership? = current.takeUnless { it?.token === finishedToken }

internal suspend fun cancelAndJoinRecoveryJob(job: Job, currentJob: Job?): Boolean {
	if (job === currentJob) return false
	job.cancelAndJoin()
	return true
}

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
	"LIBVLC_ERROR",
	-> true
	else -> false
}

internal fun isOrdinaryRecoveryErrorEligible(error: PlaybackError, entry: QueueEntry): Boolean =
	isRecoverablePlaybackError(error.codeName) && error.origin?.matches(entry) == true
