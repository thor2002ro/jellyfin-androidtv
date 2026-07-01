package org.jellyfin.playback.core.queue

import org.jellyfin.playback.core.element.ElementKey
import org.jellyfin.playback.core.element.element
import kotlin.time.Duration

private val liveStreamTargetOffsetKey = ElementKey<Duration>("LiveStreamTargetOffset")

/**
 * Preferred distance behind the live edge for live streams.
 */
var QueueEntry.liveStreamTargetOffset by element(liveStreamTargetOffsetKey)
