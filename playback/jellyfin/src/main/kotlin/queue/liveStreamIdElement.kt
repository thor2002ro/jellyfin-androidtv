package org.jellyfin.playback.jellyfin.queue

import org.jellyfin.playback.core.element.ElementKey
import org.jellyfin.playback.core.element.element
import org.jellyfin.playback.core.queue.QueueEntry

private val liveStreamIdKey = ElementKey<String>("LiveStream")

/**
 * Get or set the server-assigned live stream id used during playback.
 */
var QueueEntry.liveStreamId by element(liveStreamIdKey)
