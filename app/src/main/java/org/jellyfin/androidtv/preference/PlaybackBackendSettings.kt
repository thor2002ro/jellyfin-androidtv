package org.jellyfin.androidtv.preference

import org.jellyfin.androidtv.preference.constant.LibVLCDeblocking
import org.jellyfin.androidtv.preference.constant.LibVLCDecoder
import org.jellyfin.androidtv.preference.constant.libVLCPlaybackOptions
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.libvlc.LibVLCBackend
import org.jellyfin.playback.media3.exoplayer.ExoPlayerBackend

abstract class PlaybackBackendSettings<T : PlayerBackend>(
	private val playbackManager: PlaybackManager,
	protected val backend: T,
) {
	protected val isBackendActive: Boolean
		get() = playbackManager.isBackendActive(backend)
}

class LibVLCBackendSettings(
	private val userPreferences: UserPreferences,
	private val playbackManager: PlaybackManager,
) {
	private val activeBackend: LibVLCBackend?
		get() = playbackManager.activeBackends.firstNotNullOfOrNull { backend -> backend as? LibVLCBackend }

	fun setVideoDecoder(decoder: LibVLCDecoder) {
		activeBackend?.setVideoDecoder(decoder.decoder)
	}

	fun setPlaybackOptions(
		deblocking: LibVLCDeblocking = userPreferences[UserPreferences.libVLCDeblocking],
		frameSkip: Boolean = userPreferences[UserPreferences.libVLCFrameSkip],
		audioTimeStretch: Boolean = userPreferences[UserPreferences.libVLCAudioTimeStretch],
		dav1dThreadFrames: Int = userPreferences[UserPreferences.libVLCDav1dThreadFrames],
	) {
		activeBackend?.setPlaybackOptions(
			userPreferences.libVLCPlaybackOptions(
				deblocking = deblocking,
				frameSkip = frameSkip,
				audioTimeStretch = audioTimeStretch,
				dav1dThreadFrames = dav1dThreadFrames,
			),
		)
	}
}

class ExoPlayerBackendSettings(
	backend: ExoPlayerBackend,
	playbackManager: PlaybackManager,
) : PlaybackBackendSettings<ExoPlayerBackend>(playbackManager, backend) {
	fun invalidateRendererPreferences() {
		if (isBackendActive) backend.invalidateRendererPreferences()
	}
}
