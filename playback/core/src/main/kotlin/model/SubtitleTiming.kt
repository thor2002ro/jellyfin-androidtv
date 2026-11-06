package org.jellyfin.playback.core.model

const val DEFAULT_SUBTITLE_TIMING_SPEED = 1f
const val MIN_SUBTITLE_TIMING_SPEED = 0.5f
const val MAX_SUBTITLE_TIMING_SPEED = 2f

fun Float.coerceSubtitleTimingSpeed(): Float = when {
	!isFinite() -> DEFAULT_SUBTITLE_TIMING_SPEED
	else -> coerceIn(MIN_SUBTITLE_TIMING_SPEED, MAX_SUBTITLE_TIMING_SPEED)
}
