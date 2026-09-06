package org.jellyfin.playback.media3.exoplayer.subtitle

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.SubtitleOutputBuffer
import kotlin.math.roundToLong
import org.jellyfin.playback.core.model.DEFAULT_SUBTITLE_TIMING_SPEED

@UnstableApi
internal class OffsetSubtitleOutputBuffer(
	private val delegate: SubtitleOutputBuffer,
	private val offsetState: SubtitleTimingOffsetState,
) : SubtitleOutputBuffer(), SubtitleTimingOffsetState.TimingListener {
	@Volatile
	private var timing = SubtitleTiming(0L, DEFAULT_SUBTITLE_TIMING_SPEED)

	init {
		skippedOutputBufferCount = delegate.skippedOutputBufferCount
		shouldBeSkipped = delegate.shouldBeSkipped
		if (delegate.isEndOfStream) addFlag(C.BUFFER_FLAG_END_OF_STREAM)
		if (delegate.isFirstSample) addFlag(C.BUFFER_FLAG_FIRST_SAMPLE)
		if (delegate.isKeyFrame) addFlag(C.BUFFER_FLAG_KEY_FRAME)
		if (delegate.isLastSample) addFlag(C.BUFFER_FLAG_LAST_SAMPLE)
		if (delegate.hasSupplementalData()) addFlag(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
		if (delegate.notDependedOn()) addFlag(C.BUFFER_FLAG_NOT_DEPENDED_ON)
		offsetState.addTimingListener(this)
	}

	override fun onSubtitleTimingChanged(timing: SubtitleTiming) {
		if (!delegate.isEndOfStream) {
			this.timing = timing
			setContent(subtitleToPlaybackTime(delegate.timeUs), delegate, 0L)
		}
	}

	override fun getEventTimeCount(): Int = delegate.eventTimeCount

	override fun getEventTime(index: Int): Long =
		subtitleToPlaybackTime(delegate.getEventTime(index))

	override fun getNextEventTimeIndex(timeUs: Long): Int =
		delegate.getNextEventTimeIndex(playbackToSubtitleTime(timeUs))

	override fun getCues(timeUs: Long): List<Cue> =
		delegate.getCues(playbackToSubtitleTime(timeUs))

	private fun subtitleToPlaybackTime(timeUs: Long): Long {
		val current = timing
		return (timeUs.toDouble() / current.speed + current.offsetUs).roundToLong()
	}

	private fun playbackToSubtitleTime(timeUs: Long): Long {
		val current = timing
		return ((timeUs.toDouble() - current.offsetUs) * current.speed).roundToLong()
	}

	override fun release() {
		offsetState.removeTimingListener(this)
		delegate.release()
	}
}
