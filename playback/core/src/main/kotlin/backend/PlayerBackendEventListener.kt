package org.jellyfin.playback.core.backend

import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.VideoGeometry
import kotlin.time.Duration

abstract class PlayerBackendEventListener {
	/** A user request can arrive before the backend acknowledges its new state. */
	open fun onPlayWhenReadyRequested(playWhenReady: Boolean) = Unit
	open fun onSeekRequested(position: Duration) = Unit
	open fun onPlayStateChange(state: PlayState) = Unit
	open fun onPlaybackError(error: PlaybackError) = Unit
	open fun onVideoGeometryChange(geometry: VideoGeometry) = Unit
	/** Recovery can keep an interrupted stream selected instead of completing its queue entry. */
	open fun shouldHandleMediaStreamEnd(mediaStream: PlayableMediaStream) = true
	open fun onMediaStreamEnd(mediaStream: PlayableMediaStream) = Unit
	open fun onTracksChanged() = Unit
	open fun onSubtitleTimingOffsetSupportChange(
		supported: Boolean,
		resetTimingOnUnsupported: Boolean = true,
	) = Unit
}
