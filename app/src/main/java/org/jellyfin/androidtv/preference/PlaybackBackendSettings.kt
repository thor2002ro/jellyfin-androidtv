package org.jellyfin.androidtv.preference

import org.jellyfin.androidtv.preference.constant.LibVLCDeblocking
import org.jellyfin.androidtv.preference.constant.LibVLCDecoder
import org.jellyfin.androidtv.preference.constant.libVLCPlaybackOptions
import org.jellyfin.androidtv.preference.constant.mpvPlaybackOptions
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.libvlc.LibVLCBackend
import org.jellyfin.playback.media3.exoplayer.ExoPlayerBackend
import org.jellyfin.playback.mpv.LibMPVBackend
import org.jellyfin.playback.mpv.LibMPVOptionInfo
import org.jellyfin.playback.mpv.isLibMPVOptionManagedByJellyfin
import org.jellyfin.playback.mpv.parseLibMPVOptionOverrides
import org.jellyfin.playback.mpv.serializeLibMPVOptionOverrides

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

class LibMPVBackendSettings(
	private val userPreferences: UserPreferences,
	private val backend: LibMPVBackend,
) {
	fun applyPreferences(clearOverrides: Set<String> = emptySet()) {
		val storedOverrides = userPreferences[UserPreferences.mpvOptionOverrides]
		val overrides = parseLibMPVOptionOverrides(storedOverrides).values
		clearOverrides.forEach(overrides::remove)
		overrides.keys.removeAll(::isLibMPVOptionManagedByJellyfin)
		val serializedOverrides = serializeLibMPVOptionOverrides(overrides)
		if (serializedOverrides != storedOverrides) {
			userPreferences[UserPreferences.mpvOptionOverrides] = serializedOverrides
		}
		backend.setConfiguration(
			decoder = userPreferences[UserPreferences.mpvDecoder].decoder,
			options = userPreferences.mpvPlaybackOptions(),
		)
	}

	fun setOptionOverride(name: String, value: String?) {
		if (isLibMPVOptionManagedByJellyfin(name)) return
		val overrides = parseLibMPVOptionOverrides(userPreferences[UserPreferences.mpvOptionOverrides]).values
		if (value == null) overrides.remove(name) else overrides[name] = value
		userPreferences[UserPreferences.mpvOptionOverrides] = serializeLibMPVOptionOverrides(overrides)
		applyPreferences()
	}

	fun getOptionCatalog(): List<LibMPVOptionInfo> = backend.getOptionCatalog()
}

class ExoPlayerBackendSettings(
	backend: ExoPlayerBackend,
	playbackManager: PlaybackManager,
) : PlaybackBackendSettings<ExoPlayerBackend>(playbackManager, backend) {
	fun invalidateRendererPreferences() {
		if (isBackendActive) backend.invalidateRendererPreferences()
	}
}
