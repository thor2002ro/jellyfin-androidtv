package org.jellyfin.androidtv.test

import kotlin.math.max

data class PlaybackHealthSample(
	val decodedFrames: Int,
	val droppedFrames: Int,
	val audioDecoderName: String?,
)

data class PlaybackResourceSample(
	val openFileDescriptors: Int,
	val totalPssKb: Int,
)

data class PlaybackHealthEvaluation(val failures: List<String>)

fun evaluatePlaybackHealth(
	before: PlaybackHealthSample,
	after: PlaybackHealthSample,
	maxDroppedFrameRatio: Double = 0.10,
	requireAudioDecoder: Boolean = true,
): PlaybackHealthEvaluation {
	val decoded = (after.decodedFrames - before.decodedFrames).coerceAtLeast(0)
	val dropped = (after.droppedFrames - before.droppedFrames).coerceAtLeast(0)
	val observed = decoded + dropped
	val allowedDrops = max(5, (observed * maxDroppedFrameRatio).toInt())
	return PlaybackHealthEvaluation(buildList {
		if (decoded == 0) add("decoded frames did not increase")
		if (requireAudioDecoder && after.audioDecoderName.isNullOrBlank()) add("audio decoder was not initialized")
		if (dropped > allowedDrops) add("dropped $dropped of $observed observed frames")
	})
}

fun evaluateResourceTrend(
	samples: List<PlaybackResourceSample>,
	maxFileDescriptorGrowth: Int,
	maxMemoryGrowthKb: Int,
): PlaybackHealthEvaluation {
	if (samples.size < 2) return PlaybackHealthEvaluation(emptyList())
	val descriptors = samples.map(PlaybackResourceSample::openFileDescriptors)
	val memory = samples.map(PlaybackResourceSample::totalPssKb)
	val descriptorGrowth = descriptors.last() - descriptors.first()
	val memoryGrowth = memory.last() - memory.first()
	return PlaybackHealthEvaluation(buildList {
		if (descriptors.zipWithNext().all { (before, after) -> after >= before } && descriptorGrowth > maxFileDescriptorGrowth) {
			add("file descriptors grew by $descriptorGrowth")
		}
		if (memory.zipWithNext().all { (before, after) -> after >= before } && memoryGrowth > maxMemoryGrowthKb) {
			add("PSS grew by $memoryGrowth KiB")
		}
	})
}
