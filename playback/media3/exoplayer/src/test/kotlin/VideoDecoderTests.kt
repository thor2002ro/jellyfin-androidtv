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
})
