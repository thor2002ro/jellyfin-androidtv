package org.jellyfin.playback.media3.exoplayer.subtitle

import androidx.media3.common.util.UnstableApi
import org.jellyfin.playback.core.model.DEFAULT_SUBTITLE_TIMING_SPEED
import org.jellyfin.playback.core.model.coerceSubtitleTimingSpeed

@UnstableApi
class SubtitleTimingOffsetState @JvmOverloads constructor(
	initialOffsetUs: Long = 0L,
	initialSpeed: Float = DEFAULT_SUBTITLE_TIMING_SPEED,
) {
	internal fun interface TimingListener {
		fun onSubtitleTimingChanged(timing: SubtitleTiming)
	}

	private val timingLock = Any()
	private val timingListeners = mutableSetOf<TimingListener>()

	@Volatile
	private var timing = SubtitleTiming(initialOffsetUs, initialSpeed.coerceSubtitleTimingSpeed())

	val offsetUs: Long
		get() = timing.offsetUs
	val speed: Float
		get() = timing.speed

	fun setOffsetUs(offsetUs: Long) {
		update { it.copy(offsetUs = offsetUs) }
	}

	fun setTiming(offsetUs: Long, speed: Float) {
		update { SubtitleTiming(offsetUs, speed.coerceSubtitleTimingSpeed()) }
	}

	fun adjustOffsetUs(deltaUs: Long) {
		update { it.copy(offsetUs = it.offsetUs + deltaUs) }
	}

	internal fun addTimingListener(listener: TimingListener, notifyCurrent: Boolean = true) {
		synchronized(timingLock) {
			timingListeners.add(listener)
			if (notifyCurrent) listener.onSubtitleTimingChanged(timing)
		}
	}

	internal fun removeTimingListener(listener: TimingListener) {
		synchronized(timingLock) {
			timingListeners.remove(listener)
		}
	}

	private fun update(transform: (SubtitleTiming) -> SubtitleTiming) {
		synchronized(timingLock) {
			val updated = transform(timing)
			if (updated == timing) return
			timing = updated
			timingListeners.toList().forEach { it.onSubtitleTimingChanged(updated) }
		}
	}
}

internal data class SubtitleTiming(
	val offsetUs: Long,
	val speed: Float,
)
