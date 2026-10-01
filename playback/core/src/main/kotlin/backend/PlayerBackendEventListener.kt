package org.jellyfin.playback.core.backend

import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.VideoGeometry

abstract class PlayerBackendEventListener {
	open fun onPlayStateChange(state: PlayState) = Unit
	open fun onPlaybackError(error: PlaybackError) = Unit
	open fun onVideoGeometryChange(geometry: VideoGeometry) = Unit
	open fun onMediaStreamEnd(mediaStream: PlayableMediaStream) = Unit
	open fun onTracksChanged() = Unit
	open fun onSubtitleTimingOffsetSupportChange(
		supported: Boolean,
		resetTimingOnUnsupported: Boolean = true,
	) = Unit
}
