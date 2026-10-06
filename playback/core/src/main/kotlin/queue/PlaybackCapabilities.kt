package org.jellyfin.playback.core.queue

import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PositionInfo

val QueueEntry.isLiveTv: Boolean
	get() = liveStreamTargetOffset != null

fun QueueEntry?.canSeek(positionInfo: PositionInfo): Boolean =
	this == null || !isLiveTv || (!isDirectPlayLiveTv && mediaStream != null && positionInfo.isSeekable && positionInfo.duration.isPositive())

val QueueEntry.isDirectPlayLiveTv: Boolean
	get() = isDirectPlayLiveTv(mediaStream)

fun QueueEntry.isDirectPlayLiveTv(mediaStream: MediaStream?): Boolean =
	isLiveTv && mediaStream?.conversionMethod == MediaConversionMethod.None

val QueueEntry.isDirectStreamingLiveTv: Boolean
	get() = isDirectStreamingLiveTv(mediaStream)

fun QueueEntry.isDirectStreamingLiveTv(mediaStream: MediaStream?): Boolean =
	isLiveTv && mediaStream?.conversionMethod == MediaConversionMethod.Remux
