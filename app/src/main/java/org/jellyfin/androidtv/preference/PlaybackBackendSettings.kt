package org.jellyfin.androidtv.preference

import org.jellyfin.androidtv.preference.constant.LibVlcDeblocking
import org.jellyfin.androidtv.preference.constant.LibVlcDecoder
import org.jellyfin.androidtv.preference.constant.libVlcPlaybackOptions
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.libvlc.LibVlcBackend
import org.jellyfin.playback.media3.exoplayer.ExoPlayerBackend

abstract class PlaybackBackendSettings<T : PlayerBackend>(
	private val playbackManager: PlaybackManager,
	protected val backend: T,
) {
	protected val isBackendActive: Boolean
		get() = playbackManager.isBackendActive(backend)
}

class LibVlcBackendSettings(
	private val userPreferences: UserPreferences,
	private val playbackManager: PlaybackManager,
) {
	private val activeBackend: LibVlcBackend?
		get() = playbackManager.activeBackends.firstNotNullOfOrNull { backend -> backend as? LibVlcBackend }

	fun setVideoDecoder(decoder: LibVlcDecoder) {
		activeBackend?.setVideoDecoder(decoder.decoder)
	}

	fun setPlaybackOptions(
		deblocking: LibVlcDeblocking = userPreferences[UserPreferences.libVlcDeblocking],
		frameSkip: Boolean = userPreferences[UserPreferences.libVlcFrameSkip],
		audioTimeStretch: Boolean = userPreferences[UserPreferences.libVlcAudioTimeStretch],
		dav1dThreadFrames: Int = userPreferences[UserPreferences.libVlcDav1dThreadFrames],
	) {
		activeBackend?.setPlaybackOptions(
			userPreferences.libVlcPlaybackOptions(
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
