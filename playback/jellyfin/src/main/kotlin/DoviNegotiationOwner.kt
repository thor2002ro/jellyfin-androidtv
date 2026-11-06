package org.jellyfin.playback.jellyfin

import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.dovi.DoviDecision
import org.jellyfin.sdk.model.api.MediaSourceInfo

class DoviNegotiationOwner(
	private val queueEntry: QueueEntry,
	private val requestToken: Long,
	private val expectedDecision: DoviDecision?,
	private val validator: JellyfinDoviDecisionValidator,
) {
	private var finished = false

	fun finish(mediaSource: MediaSourceInfo?, conversionMethod: MediaConversionMethod?) {
		if (finished) return
		validator(queueEntry, requestToken, mediaSource, conversionMethod, expectedDecision)
		finished = true
	}

	suspend fun <T> run(block: suspend (DoviNegotiationOwner) -> T): T = try {
		block(this)
	} finally {
		if (!finished) finish(null, null)
	}
}
