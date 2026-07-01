package org.jellyfin.playback.jellyfin.mediastream

data class JellyfinMediaStreamOptions(
	val audioStreamIndex: Int? = null,
	val subtitleStreamIndex: Int? = null,
	val alwaysBurnInSubtitleWhenTranscoding: Boolean = false,
)
