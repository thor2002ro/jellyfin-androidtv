package org.jellyfin.playback.jellyfin.recovery

import io.github.thor2002ro.libdovi.DoviStatus
import org.jellyfin.playback.core.backend.HARDWARE_VIDEO_DECODER_ERROR_CODE
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.matches
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.dovi.DoviDecision
import org.jellyfin.playback.dovi.DoviDecisionReason
import org.jellyfin.playback.dovi.DoviRoute
import org.jellyfin.playback.dovi.DOVI_VIDEO_DECODER_ERROR_CODE
import org.jellyfin.playback.dovi.doviDecision
import org.jellyfin.playback.dovi.doviTransformFailure
import org.jellyfin.playback.dovi.doviTransformationRetryCount
import org.jellyfin.playback.dovi.doviTransformationSuppressed
import org.jellyfin.playback.dovi.doviVideoDecoderFailure
import org.jellyfin.playback.jellyfin.queue.forceTranscoding

private val DOVI_TRANSFORMATION_ERROR = Regex("^DOVI_TRANSFORMATION_FAILED_[A-Z0-9_]+$")

internal fun doviTransformationFailureStatus(codeName: String): DoviStatus? {
	if (!DOVI_TRANSFORMATION_ERROR.matches(codeName)) return null
	val suffix = codeName.removePrefix("DOVI_TRANSFORMATION_FAILED_")
	return DoviStatus.entries.firstOrNull { status -> status != DoviStatus.OK && status.name == suffix }
}

internal fun switchToTranscoding(
	entry: QueueEntry,
	error: PlaybackError,
): Boolean {
	val failure = doviTransformationFailureStatus(error.codeName)
	val decoderFailure = error.codeName == DOVI_VIDEO_DECODER_ERROR_CODE
	val hardwareFailure = error.codeName == HARDWARE_VIDEO_DECODER_ERROR_CODE
	if (failure == null && !decoderFailure && !hardwareFailure) return false
	val origin = error.origin ?: return false

	return synchronized(entry) {
		if (!origin.matches(entry)) return@synchronized false
		if (failure != null && entry.doviTransformFailure == null) entry.doviTransformFailure = failure
		if (entry.doviTransformationRetryCount == 1) return@synchronized false
		val failedDecision = entry.doviDecision
		if (!hardwareFailure && failedDecision == null) return@synchronized false
		if (failure != null && (failedDecision?.transformEvidence == null || failedDecision.request == null)) {
			return@synchronized false
		}
		if (decoderFailure && failedDecision?.route == DoviRoute.ServerFallback) return@synchronized false
		if (decoderFailure) entry.doviVideoDecoderFailure = true
		entry.doviTransformationRetryCount = 1
		if (!hardwareFailure) {
			entry.doviTransformationSuppressed = true
			entry.doviDecision = DoviDecision(
				route = DoviRoute.ServerFallback,
				reason = DoviDecisionReason.RETRY_SUPPRESSED,
			)
		}
		entry.forceTranscoding = true

		true
	}
}

internal val QueueEntry.hasTranscodeFallback: Boolean
	get() = doviTransformationRetryCount == 1 && forceTranscoding == true
