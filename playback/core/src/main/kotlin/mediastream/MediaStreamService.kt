package org.jellyfin.playback.core.mediastream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.timedevent.addTimedEvent
import org.jellyfin.playback.core.timedevent.timedEvents
import timber.log.Timber
import kotlin.time.Duration

internal class MediaStreamService(
	private val mediaStreamResolvers: Collection<MediaStreamResolver>,
	private val preloadDuration: Duration,
) : PlayerService() {
	private companion object {
		private const val TIMED_EVENT_PRELOAD = "MediaStreamServicePreloadNext"
	}

	override suspend fun onInitialize() {
		manager.queue.entry.onEach { entry ->
			Timber.d("Queue entry changed to $entry")

			if (entry == null) {
				val backend = requireNotNull(manager.backend)
				backend.stop()
			} else {
				playEntry(entry)
				entry.ensurePreloadTimedEvent()
			}
		}.launchIn(coroutineScope + Dispatchers.Main)
	}

	private suspend fun QueueEntry.ensureMediaStream(
		startPosition: Duration? = null,
	): Boolean {
		mediaStream = mediaStream ?: resolveMediaStream(startPosition)
		return mediaStream != null
	}

	private suspend fun QueueEntry.resolveMediaStream(
		startPosition: Duration? = null,
	): PlayableMediaStream? = mediaStreamResolvers.firstNotNullOfOrNull { resolver ->
		try {
			withContext(Dispatchers.IO) {
				resolver.getStream(this@resolveMediaStream, startPosition)
			}
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			Timber.e(error, "Media stream resolver failed for $this")
			null
		}
	}

	private fun QueueEntry.ensurePreloadTimedEvent() {
		if (preloadDuration <= Duration.ZERO) return

		val hasEvent = timedEvents?.any { it.key == TIMED_EVENT_PRELOAD } == true
		if (hasEvent) return

		addTimedEvent(
			TimedEvent.Block(
				key = TIMED_EVENT_PRELOAD,
				start = -preloadDuration,
				end = Duration.INFINITE,
				onActivate = { preloadNextEntry() }
			)
		)
	}

	private suspend fun playEntry(entry: QueueEntry): Boolean {
		val backend = requireNotNull(manager.backend)
		val hasMediaStream = entry.ensureMediaStream()

		if (hasMediaStream) {
			backend.playItem(entry)
			return true
		} else {
			Timber.e("Unable to resolve stream for entry $entry")

			// TODO: Somehow notify the user that we skipped an unplayable entry
			if (manager.queue.peekNext() != null) {
				manager.queue.next(usePlaybackOrder = true, useRepeatMode = false)
			} else {
				backend.stop()
			}

			return false
		}
	}

	suspend fun reloadCurrentEntry(
		position: Duration? = null,
		playWhenReady: Boolean = true,
	): Boolean {
		val entry = manager.queue.entry.value ?: return false
		val newStream = entry.resolveMediaStream(startPosition = position) ?: return false
		val backend = requireNotNull(manager.backend)

		manager.queue.entries.value.forEach { queuedEntry ->
			if (queuedEntry === entry) return@forEach
			queuedEntry.mediaStream = null
		}
		entry.mediaStream = newStream
		backend.replaceItem(entry)
		position?.let(manager.state::seek)
		if (!playWhenReady) manager.state.pause()

		return true
	}

	private fun preloadNextEntry() = coroutineScope.launch(Dispatchers.Main) {
		// Peek into the next item to preload
		val nextItem = manager.queue.peekNext() ?: return@launch

		// Preload media stream information
		val hasMediaStream = nextItem.ensureMediaStream()

		if (hasMediaStream) {
			// Preload media in backend
			val backend = requireNotNull(manager.backend)
			backend.prepareItem(nextItem)
		}
	}
}
