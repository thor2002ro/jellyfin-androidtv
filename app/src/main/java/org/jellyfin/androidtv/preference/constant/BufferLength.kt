package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.playback.core.PlaybackBufferOptions
import org.jellyfin.preference.PreferenceEnum
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val MEBIBYTE = 1024L * 1024L
private val DEFAULT_MIN_BUFFER_DURATION = 50.seconds
private val DEFAULT_PLAYBACK_BUFFER_DURATION = 1.seconds
private val DEFAULT_REBUFFER_DURATION = 2.seconds

enum class BufferLength(
	override val nameRes: Int,
	val minBufferDuration: Duration? = DEFAULT_MIN_BUFFER_DURATION,
	val maxBufferDuration: Duration? = null,
	val bufferForPlaybackDuration: Duration? = DEFAULT_PLAYBACK_BUFFER_DURATION,
	val bufferForPlaybackAfterRebufferDuration: Duration? = DEFAULT_REBUFFER_DURATION,
	val liveTvBufferDuration: Duration? = null,
	val maxBufferBytes: Long? = null,
) : PreferenceEnum {
	/**
	 * Use default buffer durations.
	 */
	AUTO(
		nameRes = R.string.playback_buffer_auto,
		liveTvBufferDuration = 2.seconds,
	),

	/**
	 * Larger buffer, suitable for moderate or variable connections.
	 */
	@Suppress("MagicNumber")
	LARGE(
		nameRes = R.string.playback_buffer_large,
		maxBufferDuration = 120.seconds,
		bufferForPlaybackDuration = 2.5.seconds,
		bufferForPlaybackAfterRebufferDuration = 5.seconds,
		liveTvBufferDuration = 5.seconds,
		maxBufferBytes = 128 * MEBIBYTE,
	),

	/**
	 * Maximum buffer, intended for slow or satellite connections.
	 */
	@Suppress("MagicNumber")
	EXTRA_LARGE(
		nameRes = R.string.playback_buffer_extra_large,
		minBufferDuration = 80.seconds,
		maxBufferDuration = 240.seconds,
		bufferForPlaybackDuration = 5.seconds,
		bufferForPlaybackAfterRebufferDuration = 10.seconds,
		liveTvBufferDuration = 10.seconds,
		maxBufferBytes = 256 * MEBIBYTE,
	),
}

fun BufferLength.toPlaybackBufferOptions() = PlaybackBufferOptions(
	minBufferDuration = minBufferDuration,
	maxBufferDuration = maxBufferDuration,
	bufferForPlaybackDuration = bufferForPlaybackDuration,
	bufferForPlaybackAfterRebufferDuration = bufferForPlaybackAfterRebufferDuration,
	liveTvBufferDuration = liveTvBufferDuration,
	maxBufferBytes = maxBufferBytes,
)
