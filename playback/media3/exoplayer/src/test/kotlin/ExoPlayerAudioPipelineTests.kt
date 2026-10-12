package org.jellyfin.playback.media3.exoplayer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ExoPlayerAudioPipelineTests : FunSpec({
	test("normalization and surround gain share one target and reset independently") {
		val pipeline = ExoPlayerAudioPipeline()
		pipeline.normalizationGain = -2f
		pipeline.surroundGain = 6
		pipeline.targetGainMillibels shouldBe 400
		pipeline.surroundGain = 0
		pipeline.targetGainMillibels shouldBe -200
		pipeline.normalizationGain = null
		pipeline.targetGainMillibels shouldBe 0
	}
})
