package org.jellyfin.playback.core.backend

data class PlaybackError(
	val codeName: String,
	val recoverWithIncreasedLiveTvOffset: Boolean = false,
)
