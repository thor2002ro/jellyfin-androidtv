package org.jellyfin.androidtv.util.sdk

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType

class VideoRangeFormattingTests : StringSpec({
	"formats standard video ranges" {
		VideoRangeType.SDR.videoRangeLabels() shouldContainExactly listOf("SDR")
		VideoRangeType.HDR10.videoRangeLabels() shouldContainExactly listOf("HDR10")
		VideoRangeType.HDR10_PLUS.videoRangeLabels() shouldContainExactly listOf("HDR10+")
		VideoRangeType.HLG.videoRangeLabels() shouldContainExactly listOf("HLG")
	}

	"formats Dolby Vision ranges with their fallback range" {
		VideoRangeType.DOVI.videoRangeLabels() shouldContainExactly listOf("DV P5")
		VideoRangeType.DOVI_WITH_EL.videoRangeLabels() shouldContainExactly listOf("DV P7")
		VideoRangeType.DOVI_WITH_HDR10.videoRangeLabels() shouldContainExactly listOf("DV P8", "HDR10")
		VideoRangeType.DOVI_WITH_HDR10_PLUS.videoRangeLabels() shouldContainExactly listOf("DV P8", "HDR10+")
		VideoRangeType.DOVI_WITH_HLG.videoRangeLabels() shouldContainExactly listOf("DV P8", "HLG")
		VideoRangeType.DOVI_WITH_SDR.videoRangeLabels() shouldContainExactly listOf("DV P8", "SDR")
		VideoRangeType.DOVI_WITH_ELHDR10_PLUS.videoRangeLabels() shouldContainExactly listOf("DV P7", "HDR10+")
	}

	"formats enum and server video range spellings consistently" {
		"HDR10_PLUS".formatVideoRange() shouldBe "HDR10+"
		"HDR10Plus".formatVideoRange() shouldBe "HDR10+"
		"DOVI_WITH_HDR10".formatVideoRange() shouldBe "DV P8 / HDR10"
		"DOVIWithHDR10Plus".formatVideoRange() shouldBe "DV P8 / HDR10+"
		"future-range".formatVideoRange() shouldBe "future-range"
		null.formatVideoRange() shouldBe null
	}

	"falls back to the coarse API range when detailed range is unavailable" {
		formatVideoRange(VideoRangeType.UNKNOWN, VideoRange.HDR) shouldBe "HDR"
		formatVideoRange(VideoRangeType.UNKNOWN, VideoRange.SDR) shouldBe "SDR"
		formatVideoRange(VideoRangeType.HDR10_PLUS, VideoRange.HDR) shouldBe "HDR10+"
		formatVideoRange(VideoRangeType.UNKNOWN, VideoRange.UNKNOWN) shouldBe null
	}

	"omits blank and known unknown range names" {
		"".formatVideoRange() shouldBe null
		"UNKNOWN".formatVideoRange() shouldBe null
		"Unknown".formatVideoRange() shouldBe null
	}
})
