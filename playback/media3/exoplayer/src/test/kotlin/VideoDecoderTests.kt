package org.jellyfin.playback.media3.exoplayer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class VideoDecoderTests : FunSpec({
	test("forced video decoder overrides the saved FFmpeg preference") {
		null.prefersFfmpeg(false) shouldBe false
		null.prefersFfmpeg(true) shouldBe true
		VideoDecoder.HARDWARE.prefersFfmpeg(true) shouldBe false
		VideoDecoder.SOFTWARE.prefersFfmpeg(true) shouldBe false
		VideoDecoder.FFMPEG.prefersFfmpeg(false) shouldBe true
	}

	test("effective video decoder falls back to the playing default") {
		null.effectiveVideoDecoder(VideoDecoder.HARDWARE, false, null) shouldBe VideoDecoder.HARDWARE
		null.effectiveVideoDecoder(VideoDecoder.HARDWARE, true, null) shouldBe VideoDecoder.FFMPEG
		null.effectiveVideoDecoder(VideoDecoder.HARDWARE, false, "ffmpeg") shouldBe VideoDecoder.FFMPEG
		null.effectiveVideoDecoder(VideoDecoder.HARDWARE, true, "OMX.test.decoder") shouldBe VideoDecoder.HARDWARE
		VideoDecoder.SOFTWARE.effectiveVideoDecoder(VideoDecoder.HARDWARE, true, "ffmpeg") shouldBe VideoDecoder.SOFTWARE
	}

	test("Live TV starts on target buffer or timeout") {
		shouldStartLivePlayback(true, 5_000, 5_000, false) shouldBe true
		shouldStartLivePlayback(true, 4_999, 5_000, false) shouldBe false
		shouldStartLivePlayback(false, 5_000, 5_000, false) shouldBe false
		shouldStartLivePlayback(false, 0, 5_000, true) shouldBe true
	}

	test("Live TV buffer uses Media3 defaults unless configured") {
		targetLiveTvBufferDuration(5.seconds, null) shouldBe null
		targetLiveTvBufferDuration(null, 5.seconds) shouldBe null
		targetLiveTvBufferDuration(10.seconds, null) shouldBe 10.seconds
		targetLiveTvBufferDuration(5.seconds, 10.seconds) shouldBe 10.seconds
		targetLiveTvBufferDuration(15.seconds, 10.seconds) shouldBe 15.seconds
	}

	test("decoder stall requires new input without new output") {
		hasDecoderStalled(10, 5, 11, 5) shouldBe true
		hasDecoderStalled(10, 5, 10, 5) shouldBe false
		hasDecoderStalled(10, 5, 11, 6) shouldBe false
	}
})
