package org.jellyfin.playback.core.model

enum class PlayState {
	STOPPED,
	PLAYING,
	BUFFERING,
	PAUSED,
	ERROR,
}

val PlayState.isActivePlayback: Boolean
	get() = this == PlayState.PLAYING || this == PlayState.BUFFERING
