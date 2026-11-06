package org.jellyfin.playback.core.mediastream

import org.jellyfin.playback.core.element.ElementKey
import org.jellyfin.playback.core.element.elementFlow
import org.jellyfin.playback.core.queue.QueueEntry

private val mediaStreamKey = ElementKey<PlayableMediaStream>("MediaStream")

/**
 * Get or set the [MediaStream] for this [QueueEntry].
 */
var QueueEntry.mediaStream: PlayableMediaStream?
	get() = getOrNull(mediaStreamKey)
	set(value) {
		if (value == null) {
			remove(mediaStreamKey)
		} else {
			if (value !== getOrNull(mediaStreamKey)) value.onAccepted?.invoke()
			put(mediaStreamKey, value)
		}
	}

/**
 * Get the [MediaStream] flow for this [QueueEntry].
 */
val QueueEntry.mediaStreamFlow by elementFlow(mediaStreamKey)
