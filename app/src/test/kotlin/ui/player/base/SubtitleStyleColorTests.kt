package org.jellyfin.androidtv.ui.player.base

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.preference.SUBTITLES_HDR_TEXT_COLOR_DEFAULT
import org.jellyfin.sdk.model.api.VideoRangeType

class SubtitleStyleColorTests : FunSpec({
	val standardColor = 0xFF112233L
	val hdrColor = 0xFF445566L

	test("only SDR streaming information uses the standard subtitle color") {
		selectSubtitleTextColor(standardColor, hdrColor, VideoRangeType.SDR) shouldBe standardColor.toInt()
	}

	test("every non-SDR streaming mode uses the HDR subtitle color") {
		(VideoRangeType.entries.filterNot { it == VideoRangeType.SDR } + null).forEach { range ->
			selectSubtitleTextColor(standardColor, hdrColor, range) shouldBe hdrColor.toInt()
		}
	}

	test("HDR subtitle color defaults to grey") {
		SUBTITLES_HDR_TEXT_COLOR_DEFAULT shouldBe 0xFF7F7F7F
	}
})
