package org.jellyfin.androidtv.util.profile

import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.dovi.DoviDecision
import org.jellyfin.playback.dovi.doviDecision
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.sdk.model.api.MediaSourceInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class DoviPlaybackNegotiationStore {
	private data class Pending(val token: Long, val plan: DoviPlaybackPlan?)

	private val nextToken = AtomicLong()
	private val entries = ConcurrentHashMap<QueueEntry, Pending>()

	fun <T> prepare(entry: QueueEntry, plan: DoviPlaybackPlan?, build: () -> T): Pair<T, Long> {
		val value = build()
		return value to begin(entry, plan)
	}

	fun begin(entry: QueueEntry, plan: DoviPlaybackPlan?): Long {
		val token = nextToken.incrementAndGet()
		entries.compute(entry) { _, _ ->
			entry.doviDecision = plan?.decision
			Pending(token, plan)
		}
		return token
	}

	fun complete(
		entry: QueueEntry,
		token: Long,
		mediaSource: MediaSourceInfo,
		expectedDecision: DoviDecision?,
	): Boolean = finish(entry, token) { request ->
		entry.retainDoviPlaybackPlanFor(request.plan, mediaSource, expectedDecision)
	}

	fun cancel(entry: QueueEntry, token: Long): Boolean = finish(entry, token) {
		entry.doviDecision = null
	}

	private inline fun finish(
		entry: QueueEntry,
		token: Long,
		crossinline apply: (Pending) -> Unit,
	): Boolean {
		var finished = false
		entries.computeIfPresent(entry) { _, request ->
			if (request.token != token) return@computeIfPresent request
			apply(request)
			finished = true
			null
		}
		return finished
	}

	internal fun hasPending(entry: QueueEntry): Boolean = entries.containsKey(entry)
}

internal fun MediaConversionMethod?.retainsDoviDecision(): Boolean =
	this == MediaConversionMethod.None || this == MediaConversionMethod.Remux
