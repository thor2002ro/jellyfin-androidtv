package org.jellyfin.playback.mpv

import org.jellyfin.playback.core.PlaybackBufferOptions
import kotlin.math.roundToLong
import kotlin.time.Duration

private const val CACHE_BITRATE_HEADROOM = 1.25

/**
 * MPV exposes cache capacity plus startup/rebuffer thresholds rather than ExoPlayer's
 * minimum/maximum buffer model. Keep every configured threshold representable, and make
 * the cache capacity at least as large as the largest wait threshold so MPV does not
 * resume early solely because the configured cache is smaller.
 */
internal data class LibMPVBufferConfiguration(
	val cacheSeconds: Double?,
	val initialWaitSeconds: Double?,
	val rebufferWaitSeconds: Double?,
)

internal fun LibMPVBufferConfiguration.cappedToBytes(bitrate: Long, maximum: Long?): LibMPVBufferConfiguration {
	if (cacheSeconds == null || bitrate <= 0 || maximum == null || maximum <= 0) return this

	val cappedCacheSeconds = minOf(cacheSeconds, maximum * 8.0 / bitrate)
	return copy(
		cacheSeconds = cappedCacheSeconds,
		initialWaitSeconds = initialWaitSeconds?.coerceAtMost(cappedCacheSeconds),
		rebufferWaitSeconds = rebufferWaitSeconds?.coerceAtMost(cappedCacheSeconds),
	)
}

internal fun PlaybackBufferOptions.toLibMPVBufferConfiguration(isLiveTv: Boolean): LibMPVBufferConfiguration {
	val liveTvSeconds = liveTvBufferDuration.positiveFiniteSeconds().takeIf { isLiveTv }
	val minimumSeconds = minBufferDuration.positiveFiniteSeconds()
	val initialWaitSeconds = liveTvSeconds ?: bufferForPlaybackDuration.positiveFiniteSeconds()
	val explicitRebufferSeconds = liveTvSeconds
		?: bufferForPlaybackAfterRebufferDuration.positiveFiniteSeconds()
		?: minimumSeconds
		?: initialWaitSeconds
	val requestedCacheSeconds = liveTvSeconds
		?: maxBufferDuration.positiveFiniteSeconds()
		?: initialWaitSeconds
		?: minimumSeconds

	return LibMPVBufferConfiguration(
		cacheSeconds = listOfNotNull(
			requestedCacheSeconds,
			minimumSeconds,
			initialWaitSeconds,
			explicitRebufferSeconds,
		).maxOrNull(),
		initialWaitSeconds = initialWaitSeconds,
		rebufferWaitSeconds = explicitRebufferSeconds,
	)
}

private fun Duration?.positiveFiniteSeconds(): Double? {
	val duration = this ?: return null
	if (!duration.isFinite() || duration <= Duration.ZERO) return null
	return duration.inWholeNanoseconds / 1_000_000_000.0
}

internal fun mpvCacheBytes(cacheSeconds: Double?, bitrate: Long, maximum: Long?): Long? {
	if (cacheSeconds == null || maximum == null || maximum <= 0) return null
	if (bitrate <= 0) return maximum
	return (cacheSeconds * bitrate / 8 * CACHE_BITRATE_HEADROOM).roundToLong().coerceAtMost(maximum)
}
