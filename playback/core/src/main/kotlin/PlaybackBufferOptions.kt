package org.jellyfin.playback.core

import kotlin.time.Duration

data class PlaybackBufferOptions(
	val minBufferDuration: Duration? = null,
	val maxBufferDuration: Duration? = null,
	val bufferForPlaybackDuration: Duration? = null,
	val bufferForPlaybackAfterRebufferDuration: Duration? = null,
	val liveTvBufferDuration: Duration? = null,
	val maxBufferBytes: Long? = null,
)

/** Returns the effective byte budget for this stream’s requested buffer duration. */
fun playbackCacheCeiling(cacheSeconds: Double?, bitrate: Long, maximum: Long?): Long? {
	if (cacheSeconds == null || bitrate <= 0 || maximum == null || maximum <= 0) return maximum
	if (cacheSeconds <= maximum * 8.0 / bitrate) return maximum
	return maximum + minOf(maximum / 2, Long.MAX_VALUE - maximum)
}
