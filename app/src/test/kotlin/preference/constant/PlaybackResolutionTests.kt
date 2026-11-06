package org.jellyfin.androidtv.preference.constant

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlaybackResolutionTests : FunSpec({
	test("native keeps reported resolution caps") {
		PlaybackResolution.NATIVE.capWidth(3840) shouldBe 3840
		PlaybackResolution.NATIVE.capHeight(2160) shouldBe 2160
	}

	test("preset only lowers reported resolution caps") {
		PlaybackResolution.FULL_HD_1080.capWidth(3840) shouldBe 1920
		PlaybackResolution.FULL_HD_1080.capHeight(2160) shouldBe 1080
		PlaybackResolution.FULL_HD_1080.capWidth(1280) shouldBe 1280
		PlaybackResolution.FULL_HD_1080.capHeight(720) shouldBe 720
	}
})
