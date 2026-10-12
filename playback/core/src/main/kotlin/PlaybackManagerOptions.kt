package org.jellyfin.playback.core

import kotlin.time.Duration
import org.jellyfin.playback.core.mediastream.PlayableMediaStream

class PlaybackManagerOptions(
	val playerVolumeState: PlayerVolumeState,

	val defaultRewindAmount: () -> Duration,
	val defaultFastForwardAmount: () -> Duration,
) {
	/**
	 * Complete window preparation before the backend opens or replaces a stream.
	 * The video screen supplies this callback because it owns the Activity window.
	 * Returning false leaves the backend untouched; null requires no preparation.
	 */
	var beforePlayItem: (suspend (PlayableMediaStream) -> Boolean)? = null
}
