package org.jellyfin.playback.media3.exoplayer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class VideoDecoderTests : FunSpec({
	test("forced video decoder overrides the saved FFmpeg preference") {
		null.prefersFfmpeg(false) shouldBe false
		null.prefersFfmpeg(true) shouldBe true
		VideoDecoder.HARDWARE.prefersFfmpeg(true) shouldBe false
		VideoDecoder.SOFTWARE.prefersFfmpeg(true) shouldBe false
		VideoDecoder.FFMPEG.prefersFfmpeg(false) shouldBe true
	}

	test("Live TV starts on target buffer or timeout") {
		shouldStartLivePlayback(true, 5_000, 5_000, false) shouldBe true
		shouldStartLivePlayback(true, 4_999, 5_000, false) shouldBe false
		shouldStartLivePlayback(false, 5_000, 5_000, false) shouldBe false
		shouldStartLivePlayback(false, 0, 5_000, true) shouldBe true
	}

	test("decoder stall requires new input without new output") {
		hasDecoderStalled(10, 5, 11, 5) shouldBe true
		hasDecoderStalled(10, 5, 10, 5) shouldBe false
		hasDecoderStalled(10, 5, 11, 6) shouldBe false
	}
})
