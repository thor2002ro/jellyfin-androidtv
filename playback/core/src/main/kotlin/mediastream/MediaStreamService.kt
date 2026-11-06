package org.jellyfin.playback.core.mediastream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.resetSubtitleTiming
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.activate
import org.jellyfin.playback.core.backend.createPlaybackErrorOrigin
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.QueueService
import org.jellyfin.playback.core.queue.isLiveTv
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.timedevent.addTimedEvent
import org.jellyfin.playback.core.timedevent.timedEvents
import timber.log.Timber
import java.io.IOException
import kotlin.time.Duration

enum class MediaStreamReloadResult {
	RELOADED,
	FAILED,
	SUPERSEDED,
}

internal class MediaStreamService(
	private val mediaStreamResolvers: Collection<MediaStreamResolver>,
	private val preloadDuration: Duration,
) : PlayerService() {
	private companion object {
		private const val TIMED_EVENT_PRELOAD = "MediaStreamServicePreloadNext"
	}
	private var latestStreamRequest: Any? = null
	private val _preparingEntry = MutableStateFlow<QueueEntry?>(null)
	val preparingEntry = _preparingEntry.asStateFlow()

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
	): PlayableMediaStream? {
		var networkError: IOException? = null
		val stream = mediaStreamResolvers.firstNotNullOfOrNull { resolver ->
			try {
				withContext(Dispatchers.IO) {
					resolver.getStream(this@resolveMediaStream, startPosition)
				}
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				Timber.e(error, "Media stream resolver failed for $this")
				// SDKs wrap timeouts and connection failures; preserve them for playback recovery.
				generateSequence<Throwable>(error) { it.cause }.filterIsInstance<IOException>().firstOrNull()?.let {
					networkError = it
				}
				null
			}
		}
		if (stream == null) networkError?.let { throw it }
		return stream
	}

	private fun QueueEntry.ensurePreloadTimedEvent() {
		if (preloadDuration <= Duration.ZERO) return
		if (isLiveTv) return

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

	private suspend fun <T> prepareEntry(entry: QueueEntry, action: suspend (Any) -> T): T {
		val request = Any().also { latestStreamRequest = it }
		_preparingEntry.value = entry
		return try {
			action(request)
		} finally {
			if (latestStreamRequest === request) _preparingEntry.value = null
		}
	}

	internal suspend fun playEntry(entry: QueueEntry): Boolean = prepareEntry(entry) { request ->
		resolveAndPlayEntry(entry, request)
	}

	private suspend fun resolveAndPlayEntry(entry: QueueEntry, request: Any): Boolean {
		val backend = requireNotNull(manager.backend)
		val queue = requireNotNull(manager.getService<QueueService>())
		val entryRevision = queue.entryRevision
		val originalStream = entry.mediaStream
		val startPosition = entry.startPosition?.coerceAtLeast(Duration.ZERO)
		entry.startPosition = startPosition
		var networkError: IOException? = null
		val resolvedStream = try {
			originalStream ?: entry.resolveMediaStream(startPosition)
		} catch (error: IOException) {
			networkError = error
			null
		}
		// A late failure must not stop or recover a different queue selection or backend.
		if (latestStreamRequest !== request || entry.mediaStream !== originalStream ||
			queue.entry.value !== entry || queue.entryRevision != entryRevision || manager.backend !== backend
		) return false

		if (resolvedStream != null) {
			val stream = resolvedStream.also { entry.mediaStream = it }
			// Opening the decoder must follow display preparation. Publishing the stream
			// alone is insufficient: a UI effect reacting to it can run after playItem().
			val preparation = manager.options.beforePlayItem
			if (preparation?.invoke(stream) == false) return false
			// The wait also gives the user time to stop, switch players, or leave the screen.
			// Callback identity detects a screen replacement even if the queue stayed the same.
			if (latestStreamRequest !== request || queue.entry.value !== entry || queue.entryRevision != entryRevision ||
				manager.backend !== backend || entry.mediaStream !== stream ||
				manager.options.beforePlayItem !== preparation
			) return false
			manager.state.resetSubtitleTiming()
			backend.playItem(entry)
			entry.startPosition = null
			return true
		} else {
			Timber.e("Unable to resolve stream for entry $entry")
			if (networkError != null) {
				backend.stop()
				val origin = entry.createPlaybackErrorOrigin("stream-resolution").also { it.activate() }
				manager.backendService.reportPlaybackError(PlaybackError("MEDIA_STREAM_NETWORK_ERROR", origin = origin))
				return false
			}

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
	): MediaStreamReloadResult {
		val entry = manager.queue.entry.value ?: return MediaStreamReloadResult.SUPERSEDED
		return prepareEntry(entry) { request -> reloadEntry(entry, request, position, playWhenReady) }
	}

	private suspend fun reloadEntry(
		entry: QueueEntry,
		request: Any,
		position: Duration?,
		playWhenReady: Boolean,
	): MediaStreamReloadResult {
		val queue = requireNotNull(manager.getService<QueueService>())
		val entryRevision = queue.entryRevision
		val originalStream = entry.mediaStream
		val backend = manager.backend
		val newStream = try {
			entry.resolveMediaStream(startPosition = position)
		} catch (error: IOException) {
			null
		}
		// Resolution suspends for the server: playback may have stopped, changed, or been reloaded meanwhile.
		if (
			latestStreamRequest !== request ||
			queue.entryRevision != entryRevision ||
			queue.entry.value !== entry ||
			entry.mediaStream !== originalStream ||
			manager.backend !== backend
		) return MediaStreamReloadResult.SUPERSEDED
		if (newStream == null) return MediaStreamReloadResult.FAILED

		// Reloads can change video dimensions too. Prepare for the new stream before
		// publishing it or asking the backend to replace the currently playing stream.
		val preparation = manager.options.beforePlayItem
		if (preparation?.invoke(newStream) == false) return MediaStreamReloadResult.SUPERSEDED
		// Window preparation suspends too: reject a stopped, replaced, or superseded entry.
		// The revision also catches stopping and selecting the same QueueEntry again.
		if (
			latestStreamRequest !== request ||
			queue.entryRevision != entryRevision ||
			queue.entry.value !== entry ||
			entry.mediaStream !== originalStream ||
			manager.backend !== backend ||
			manager.options.beforePlayItem !== preparation
		) return MediaStreamReloadResult.SUPERSEDED

		manager.queue.entries.value.forEach { queuedEntry ->
			if (queuedEntry === entry) return@forEach
			queuedEntry.mediaStream = null
		}
		entry.mediaStream = newStream
		entry.startPosition = position?.coerceAtLeast(Duration.ZERO)
		backend.replaceItem(entry)
		entry.startPosition = null
		if (!playWhenReady) manager.state.pause()

		return MediaStreamReloadResult.RELOADED
	}

	private fun preloadNextEntry() = coroutineScope.launch(Dispatchers.Main) {
		if (manager.queue.entry.value?.isLiveTv == true) return@launch

		// Peek into the next item to preload
		val nextItem = manager.queue.peekNext() ?: return@launch

		// Preload media stream information
		val hasMediaStream = try {
			nextItem.ensureMediaStream()
		} catch (error: IOException) {
			return@launch
		}

		if (hasMediaStream) {
			// Preload media in backend
			val backend = requireNotNull(manager.backend)
			backend.prepareItem(nextItem)
		}
	}
}
