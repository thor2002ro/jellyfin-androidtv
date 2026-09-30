package org.jellyfin.playback.core.mediastream

import org.jellyfin.playback.core.element.ElementKey
import org.jellyfin.playback.core.element.element
import org.jellyfin.playback.core.queue.QueueEntry
import kotlin.time.Duration

private val startPositionKey = ElementKey<Duration>("StartPosition")

var QueueEntry.startPosition by element(startPositionKey)
