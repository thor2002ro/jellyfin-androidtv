package org.jellyfin.playback.core.backend

import org.jellyfin.playback.core.element.ElementKey
import org.jellyfin.playback.core.element.element
import org.jellyfin.playback.core.queue.QueueEntry

class PlaybackLoadToken private constructor() {
	companion object {
		fun create(): PlaybackLoadToken = PlaybackLoadToken()
	}
}

data class PlaybackErrorOrigin(
	val queueEntry: QueueEntry,
	val sourceIdentity: String,
	val loadToken: PlaybackLoadToken,
)

fun QueueEntry.createPlaybackErrorOrigin(sourceIdentity: String): PlaybackErrorOrigin =
	PlaybackErrorOrigin(this, sourceIdentity, PlaybackLoadToken.create())

private val activePlaybackErrorOriginKey = ElementKey<PlaybackErrorOrigin>("ActivePlaybackErrorOrigin")

var QueueEntry.activePlaybackErrorOrigin: PlaybackErrorOrigin? by element(activePlaybackErrorOriginKey)

fun PlaybackErrorOrigin.activate() {
	queueEntry.activePlaybackErrorOrigin = this
}

fun PlaybackErrorOrigin.matches(entry: QueueEntry?): Boolean =
	entry != null && queueEntry === entry && entry.activePlaybackErrorOrigin === this

data class PlaybackError(
	val codeName: String,
	val recoverWithIncreasedLiveTvOffset: Boolean = false,
	val origin: PlaybackErrorOrigin? = null,
)
