package org.jellyfin.playback.core

import kotlin.time.Duration

data class PlaybackBufferOptions(
	val minBufferDuration: Duration? = null,
	val maxBufferDuration: Duration? = null,
	val bufferForPlaybackDuration: Duration? = null,
	val bufferForPlaybackAfterRebufferDuration: Duration? = null,
	val liveTvBufferDuration: Duration? = null,
)
