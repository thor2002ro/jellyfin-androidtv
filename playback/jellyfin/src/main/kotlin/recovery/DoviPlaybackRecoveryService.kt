package org.jellyfin.playback.jellyfin.recovery

import io.github.thor2002ro.libdovi.DoviStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.PlaybackErrorOrigin
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.backend.matches
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.isActivePlayback
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.liveStreamTargetOffset
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.dovi.DoviDecision
import org.jellyfin.playback.dovi.DoviDecisionReason
import org.jellyfin.playback.dovi.DoviRoute
import org.jellyfin.playback.dovi.DoviTransformEvidence
import org.jellyfin.playback.dovi.DoviRecoveryOwnership
import org.jellyfin.playback.dovi.DOVI_VIDEO_DECODER_ERROR_CODE
import org.jellyfin.playback.dovi.doviDecision
import org.jellyfin.playback.dovi.doviRecoveryOwnership
import org.jellyfin.playback.dovi.doviTransformFailure
import org.jellyfin.playback.dovi.doviTransformationRetryCount
import org.jellyfin.playback.dovi.doviTransformationSuppressed
import org.jellyfin.playback.dovi.doviVideoDecoderFailure
import org.jellyfin.playback.jellyfin.queue.forceTranscoding
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

private val DOVI_TRANSFORMATION_ERROR = Regex("^DOVI_TRANSFORMATION_FAILED_[A-Z0-9_]+$")

internal data class DoviTransformationRecovery(
	val entry: QueueEntry,
	val origin: PlaybackErrorOrigin,
	val failedTransform: DoviTransformEvidence?,
	val position: Duration?,
	val playWhenReady: Boolean,
) {
	fun stillMatches(currentEntry: QueueEntry?): Boolean =
		origin.matches(currentEntry) &&
			entry.doviTransformationSuppressed == true
}

internal class DoviTransformationRecoveryRunner {
	private val activeEntries = ConcurrentHashMap.newKeySet<QueueEntry>()

	suspend fun run(
		recovery: DoviTransformationRecovery,
		currentEntry: () -> QueueEntry?,
		handoff: suspend (QueueEntry) -> Unit,
		reload: suspend (position: Duration?, playWhenReady: Boolean) -> Boolean,
	): Boolean {
		if (!activeEntries.add(recovery.entry)) return false
		return try {
			if (!recovery.stillMatches(currentEntry())) {
				recovery.entry.doviRecoveryOwnership = DoviRecoveryOwnership.TERMINAL
				return false
			}
			handoff(recovery.entry)
			if (currentEntry() !== recovery.entry || recovery.entry.doviRecoveryOwnership != DoviRecoveryOwnership.IN_FLIGHT) {
				recovery.entry.doviRecoveryOwnership = DoviRecoveryOwnership.TERMINAL
				return false
			}
			reload(recovery.position, recovery.playWhenReady).also { succeeded ->
				if (!succeeded) recovery.entry.doviRecoveryOwnership = DoviRecoveryOwnership.TERMINAL
			}
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			Timber.e(error, "Unable to complete Dolby Vision server fallback handoff")
			recovery.entry.doviRecoveryOwnership = DoviRecoveryOwnership.TERMINAL
			false
		} finally {
			activeEntries.remove(recovery.entry)
		}
	}

	internal fun isActive(entry: QueueEntry): Boolean = entry in activeEntries
}

class DoviRecoveryHandoffCoordinator {
	private val handlers = mutableListOf<suspend (QueueEntry) -> Unit>()

	fun register(handler: suspend (QueueEntry) -> Unit) {
		synchronized(handlers) { handlers += handler }
	}

	suspend fun cancelAndJoin(entry: QueueEntry) {
		val snapshot = synchronized(handlers) { handlers.toList() }
		snapshot.forEach { handler -> handler(entry) }
	}
}

class DoviPlaybackRecoveryService(
	private val handoffCoordinator: DoviRecoveryHandoffCoordinator,
) : PlayerService() {
	private val runner = DoviTransformationRecoveryRunner()

	override suspend fun onInitialize() {
		manager.addBackendEventListener(object : PlayerBackendEventListener() {
			override fun onPlayStateChange(state: PlayState) {
				val entry = manager.queue.entry.value ?: return
				completeDoviRecoveryOnPlaybackState(entry, state)
			}

			override fun onPlaybackError(error: PlaybackError) {
				val entry = manager.queue.entry.value ?: return
				val recovery = claimDoviTransformationRecovery(
					entry = entry,
					error = error,
					position = state.positionInfo.active.takeUnless { entry.liveStreamTargetOffset != null },
					playWhenReady = state.playState.value.isActivePlayback,
				) ?: return

				coroutineScope.launch(Dispatchers.Main) {
					Timber.w("Dolby Vision playback failed; retrying once with server fallback")
					if (!runner.run(
							recovery = recovery,
							currentEntry = { manager.queue.entry.value },
							handoff = handoffCoordinator::cancelAndJoin,
							reload = manager::reloadCurrentMediaStream,
						)) {
						Timber.e("Unable to reload Dolby Vision stream with server fallback")
					}
				}
			}
		})
	}
}

internal fun completeDoviRecoveryOnPlaybackState(entry: QueueEntry, state: PlayState) {
	if (state != PlayState.PLAYING && state != PlayState.PAUSED) return
	if (entry.doviRecoveryOwnership == DoviRecoveryOwnership.IN_FLIGHT) {
		entry.doviRecoveryOwnership = null
	}
}

internal fun blocksGenericRecovery(entry: QueueEntry): Boolean = entry.doviRecoveryOwnership != null

internal fun isDoviTransformationError(codeName: String): Boolean =
	doviTransformationFailureStatus(codeName) != null

internal fun doviTransformationFailureStatus(codeName: String): DoviStatus? {
	if (!DOVI_TRANSFORMATION_ERROR.matches(codeName)) return null
	val suffix = codeName.removePrefix("DOVI_TRANSFORMATION_FAILED_")
	return DoviStatus.entries.firstOrNull { status -> status != DoviStatus.OK && status.name == suffix }
}

internal fun claimDoviTransformationRecovery(
	entry: QueueEntry,
	error: PlaybackError,
	position: Duration?,
	playWhenReady: Boolean,
): DoviTransformationRecovery? {
	val failure = doviTransformationFailureStatus(error.codeName)
	val decoderFailure = error.codeName == DOVI_VIDEO_DECODER_ERROR_CODE
	if (failure == null && !decoderFailure) return null
	val origin = error.origin?.takeIf { it.matches(entry) } ?: return null

	return synchronized(entry) {
		if (!origin.matches(entry)) return@synchronized null
		if (failure != null && entry.doviTransformFailure == null) entry.doviTransformFailure = failure
		if ((entry.doviTransformationRetryCount ?: 0) >= 1) {
			entry.doviRecoveryOwnership = DoviRecoveryOwnership.TERMINAL
			return@synchronized null
		}
		val failedDecision = entry.doviDecision ?: return@synchronized null
		if (failure != null && (failedDecision.transformEvidence == null || failedDecision.request == null)) {
			return@synchronized null
		}
		if (decoderFailure && failedDecision.route == DoviRoute.ServerFallback) return@synchronized null
		val failedTransform = failedDecision.transformEvidence
		if (decoderFailure) entry.doviVideoDecoderFailure = true
		entry.doviTransformationRetryCount = 1
		entry.doviTransformationSuppressed = true
		entry.doviRecoveryOwnership = DoviRecoveryOwnership.IN_FLIGHT
		entry.doviDecision = DoviDecision(
			route = DoviRoute.ServerFallback,
			reason = DoviDecisionReason.RETRY_SUPPRESSED,
		)
		entry.forceTranscoding = true

		DoviTransformationRecovery(
			entry = entry,
			origin = origin,
			failedTransform = failedTransform,
			position = position,
			playWhenReady = playWhenReady,
		)
	}
}
