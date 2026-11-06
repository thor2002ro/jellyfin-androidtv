package org.jellyfin.playback.core.queue

import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.mediaStream

val QueueEntry.isLiveTv: Boolean
	get() = liveStreamTargetOffset != null

val QueueEntry.isDirectStreamingLiveTv: Boolean
	get() = isDirectStreamingLiveTv(mediaStream)

fun QueueEntry.isDirectStreamingLiveTv(mediaStream: MediaStream?): Boolean =
	isLiveTv && mediaStream?.conversionMethod == MediaConversionMethod.Remux
