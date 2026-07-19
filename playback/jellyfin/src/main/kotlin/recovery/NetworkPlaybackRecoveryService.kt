package org.jellyfin.playback.jellyfin.recovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.isActivePlayback
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.livetv.LiveTvPlaybackPolicy
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class NetworkPlaybackRecoveryService(
	private val liveTvPlaybackPolicy: LiveTvPlaybackPolicy,
	private val networkAvailable: () -> Boolean = { true },
) : PlayerService() {
	private var recoveryJob: Job? = null
	private var recoveringEntry: QueueEntry? = null
	private var errorRecoveryAttemptedEntry: QueueEntry? = null
	private val _recovering = MutableStateFlow(false)
	val recovering: StateFlow<Boolean> = _recovering.asStateFlow()

	override suspend fun onInitialize() {
		manager.addBackendEventListener(object : PlayerBackendEventListener() {
			override fun onPlaybackError(error: PlaybackError) {
				val entry = manager.queue.entry.value
				if (
					entry == null ||
					liveTvPlaybackPolicy.isLiveTv(entry) ||
					!isRecoverablePlaybackError(error.codeName)
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

			while (true) {
				delay(NETWORK_RECOVERY_CHECK_INTERVAL)

				val entry = manager.queue.entry.value
				val playState = state.playState.value
				if (
					entry !== errorRecoveryAttemptedEntry ||
					recoveryJob?.isActive != true &&
					(playState == PlayState.PLAYING || playState == PlayState.PAUSED)
				) {
					errorRecoveryAttemptedEntry = null
				}
				if (entry == null || liveTvPlaybackPolicy.isLiveTv(entry) || playState == PlayState.STOPPED) {
					disconnectedEntry = null
					clearRecovering()
					wasNetworkAvailable = isNetworkAvailable()
					lastPlayState = playState
					continue
				}

				val networkAvailable = isNetworkAvailable()
				if (!networkAvailable) {
					if (disconnectedEntry == null && (playState.isActivePlayback || lastPlayState.isActivePlayback || playState == PlayState.ERROR)) {
						Timber.w("Network unavailable during playback; waiting for connectivity to return")
						disconnectedEntry = entry
						setRecovering(entry)
					}
					wasNetworkAvailable = false
					lastPlayState = playState
					continue
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
		if (recoveryJob?.isActive == true) {
			if (recoveringEntry === entry) return
			recoveryJob?.cancel()
		}
		setRecovering(entry)

		recoveryJob = coroutineScope.launch(Dispatchers.Main) {
			try {
				recoverEntry(entry, reason, playWhenReady)
			} finally {
				if (recoveryJob === coroutineContext[Job]) {
					recoveryJob = null
				}
				clearRecovering(entry)
			}
		}
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
			!liveTvPlaybackPolicy.isLiveTv(entry) &&
			state.playState.value != PlayState.STOPPED
	}

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
	}
}

internal fun hasPlaybackRecovered(
	playState: PlayState,
	positionBeforeGrace: Duration,
	positionAfterGrace: Duration,
) = playState == PlayState.PAUSED ||
	playState == PlayState.PLAYING && positionAfterGrace > positionBeforeGrace

internal fun isRecoverablePlaybackError(codeName: String) = when (codeName) {
	"ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
	"ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
	"LIBVLC_ERROR",
	-> true
	else -> false
}
