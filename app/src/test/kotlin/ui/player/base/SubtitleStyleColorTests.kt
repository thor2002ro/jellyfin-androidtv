package org.jellyfin.androidtv.ui.player.base

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.preference.SUBTITLES_HDR_TEXT_COLOR_DEFAULT

class SubtitleStyleColorTests : FunSpec({
	val standardColor = 0xFF112233L
	val hdrColor = 0xFF445566L

	test("SDR playback uses the standard subtitle color") {
		selectSubtitleTextColor(standardColor, hdrColor, videoIsHdr = false) shouldBe standardColor.toInt()
	}

	test("unknown playback color uses the standard subtitle color") {
		selectSubtitleTextColor(standardColor, hdrColor, videoIsHdr = null) shouldBe standardColor.toInt()
	}

	test("HDR playback uses the HDR subtitle color") {
		selectSubtitleTextColor(standardColor, hdrColor, videoIsHdr = true) shouldBe hdrColor.toInt()
	}

	test("HDR subtitle color defaults to grey") {
		SUBTITLES_HDR_TEXT_COLOR_DEFAULT shouldBe 0xFF7F7F7F
	}
})
