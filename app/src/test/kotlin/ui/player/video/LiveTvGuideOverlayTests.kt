package org.jellyfin.androidtv.ui.player.video

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDateTime
import java.util.UUID

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

	test("guide preserves the selected channel when channels refresh") {
		val channels = List(3) { UUID.randomUUID() }
		val refreshedChannels = listOf(channels[1], channels[2], channels[0])

		guideChannelIndex(refreshedChannels, channels[2], channels[0]) shouldBe 1
		guideChannelIndex(refreshedChannels, UUID.randomUUID(), channels[0]) shouldBe 2
	}

	test("program details stay on the final program after the schedule ends") {
		val start = LocalDateTime.of(2026, 7, 13, 18, 0)
		val first = liveTvProgram("First", start, start.plusMinutes(30))
		val last = liveTvProgram("Last", start.plusMinutes(30), start.plusMinutes(60))

		listOf(last, first).programAt(start.plusMinutes(60)) shouldBe last
	}

	test("guide time range aligns program boundaries and excludes the exact end") {
		val start = LocalDateTime.of(2026, 7, 13, 18, 7)
		val program = liveTvProgram("Program", start, LocalDateTime.of(2026, 7, 13, 19, 0))

		mapOf(UUID.randomUUID() to listOf(program)).guideTimeRange() shouldBe
			(LocalDateTime.of(2026, 7, 13, 18, 0) to LocalDateTime.of(2026, 7, 13, 18, 30))
	}
})

private fun liveTvProgram(name: String, start: LocalDateTime, end: LocalDateTime) = BaseItemDto(
	id = UUID.randomUUID(),
	type = BaseItemKind.LIVE_TV_PROGRAM,
	name = name,
	startDate = start,
	endDate = end,
)
