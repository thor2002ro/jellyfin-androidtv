package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class VideoDecoderTests : FunSpec({
	test("video color info is exposed as player metrics") {
		val format = Format.Builder()
			.setColorInfo(
				ColorInfo.Builder()
					.setColorSpace(C.COLOR_SPACE_BT2020)
					.setColorTransfer(C.COLOR_TRANSFER_ST2084)
					.setColorRange(C.COLOR_RANGE_LIMITED)
					.build()
			)
			.build()

		format.colorDetails() shouldBe mapOf(
			"Color space" to "BT.2020",
			"Color transfer" to "PQ (ST 2084)",
			"Color range" to "Limited",
		)
	}

	test("forced video decoder overrides the saved FFmpeg preference") {
		null.prefersFfmpeg(false) shouldBe false
		null.prefersFfmpeg(true) shouldBe true
		VideoDecoder.HARDWARE.prefersFfmpeg(true) shouldBe false
		VideoDecoder.SOFTWARE.prefersFfmpeg(true) shouldBe false
		VideoDecoder.FFMPEG.prefersFfmpeg(false) shouldBe true
	}
})
