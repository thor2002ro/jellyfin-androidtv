package org.jellyfin.androidtv.ui.player.video

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class StatsForNerdsHdrTests : StringSpec({
	"direct HDR10+ metadata fills in an unavailable player report" {
		streamingHdrMode(
			playerHdrMode = null,
			streamHdrMode = "HDR10+",
			isVideoDirect = null,
		) shouldBe "HDR10+"
		streamingHdrMode(
			playerHdrMode = "SDR (BT.1886)",
			streamHdrMode = "HDR10+",
			isVideoDirect = null,
		) shouldBe "SDR (BT.1886)"
	}

	"other player HDR reports remain authoritative" {
		streamingHdrMode("HDR10", "HDR10", null) shouldBe "HDR10"
		streamingHdrMode("Dolby Vision (Profile 8)", "DV P8", null) shouldBe "Dolby Vision (Profile 8)"
		streamingHdrMode("SDR (BT.1886)", "HDR10", null) shouldBe "SDR (BT.1886)"
	}

	"video transcoding does not reuse source HDR10+ metadata" {
		streamingHdrMode("SDR (BT.1886)", "HDR10+", false) shouldBe "SDR (BT.1886)"
	}
})
