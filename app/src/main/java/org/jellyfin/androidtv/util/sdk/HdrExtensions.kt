package org.jellyfin.androidtv.util.sdk

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

val BaseItemDto.playbackMediaSource get() = mediaSources
	?.firstOrNull { source -> source.id?.toUUIDOrNull() == id }
	?: mediaSources?.firstOrNull()

val BaseItemDto.isHdrVideo get() = playbackMediaSource
	?.mediaStreams.orEmpty()
	.any { stream ->
		stream.type == MediaStreamType.VIDEO &&
			stream.videoRangeType != VideoRangeType.UNKNOWN &&
			stream.videoRangeType != VideoRangeType.SDR
	}
