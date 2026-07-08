package org.jellyfin.androidtv.ui.player.video

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class LiveTvGuideOverlayTests : FunSpec({
	test("current time line follows the visible guide window") {
		val start = LocalDateTime.of(2026, 7, 10, 12, 0)

		currentTimeFraction(start, start.plusMinutes(75)) shouldBe 0.5f
		currentTimeFraction(start, start.minusMinutes(1)) shouldBe null
		currentTimeFraction(start, start.plusMinutes(151)) shouldBe null
	}

	test("guide starts on a half-hour boundary") {
		guideStartTime(LocalDateTime.of(2026, 7, 10, 18, 49, 37)) shouldBe
			LocalDateTime.of(2026, 7, 10, 18, 30)
	}

	test("guide age follows the last successful EPG load") {
		val loadedAt = LocalDateTime.of(2026, 7, 10, 18, 30)

		guideAgeMinutes(loadedAt, loadedAt.plusMinutes(7)) shouldBe 7
		guideAgeMinutes(loadedAt, loadedAt.minusMinutes(1)) shouldBe 0
	}
})
