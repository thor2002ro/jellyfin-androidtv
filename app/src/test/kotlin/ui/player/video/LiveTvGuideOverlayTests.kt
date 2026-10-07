package org.jellyfin.androidtv.ui.player.video

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDateTime
import java.util.UUID

class LiveTvGuideOverlayTests : FunSpec({
	test("guide displays every short program inside a half hour") {
		val start = LocalDateTime.of(2026, 9, 11, 18, 0)
		val programs = listOf(
			liveTvProgram("A", start, start.plusMinutes(10)),
			liveTvProgram("B", start.plusMinutes(10), start.plusMinutes(20)),
			liveTvProgram("C", start.plusMinutes(20), start.plusMinutes(30)),
		)

		programs.programBlocks(listOf(start)).map { it.program?.name } shouldContainExactly listOf("A", "B", "C")
	}

	test("guide moves through short programs in both directions") {
		val start = LocalDateTime.of(2026, 9, 11, 18, 0)
		val programs = listOf(
			liveTvProgram("A", start, start.plusMinutes(10)),
			liveTvProgram("B", start.plusMinutes(10), start.plusMinutes(20)),
			liveTvProgram("C", start.plusMinutes(20), start.plusMinutes(30)),
		)

		programs.adjacentGuideTime(start, 30) shouldBe start.plusMinutes(10)
		programs.adjacentGuideTime(start.plusMinutes(10), 30) shouldBe start.plusMinutes(20)
		programs.adjacentGuideTime(start.plusMinutes(20), -30) shouldBe start.plusMinutes(10)
	}

	test("guide preserves gaps between programs") {
		val start = LocalDateTime.of(2026, 9, 11, 18, 0)
		val programs = listOf(
			liveTvProgram("A", start, start.plusMinutes(10)),
			liveTvProgram("B", start.plusMinutes(20), start.plusMinutes(30)),
		)

		programs.programBlocks(listOf(start)).map { it.program?.name } shouldContainExactly listOf("A", null, "B")
		programs.adjacentGuideTime(start, 30) shouldBe start.plusMinutes(10)
		programs.adjacentGuideTime(start.plusMinutes(10), 30) shouldBe start.plusMinutes(20)
	}

	test("selecting a schedule gap does not offer recording the next program") {
		val start = LocalDateTime.of(2026, 9, 11, 18, 0)
		val programs = listOf(
			liveTvProgram("A", start, start.plusMinutes(10)),
			liveTvProgram("B", start.plusMinutes(20), start.plusMinutes(30)),
		)

		programs.programAt(start.plusMinutes(10)) shouldBe null
	}

	test("selecting a leading gap does not offer recording the first program") {
		val start = LocalDateTime.of(2026, 9, 11, 18, 0)
		val programs = listOf(liveTvProgram("A", start.plusMinutes(7), start.plusMinutes(30)))

		programs.programBlocks(listOf(start)).map { it.program?.name } shouldContainExactly listOf(null, "A")
		programs.programAt(start) shouldBe null
	}

	test("guide clips programs to the viewport using their real duration") {
		val start = LocalDateTime.of(2026, 9, 11, 18, 0)
		val programs = listOf(
			liveTvProgram("A", start.minusMinutes(5), start.plusMinutes(10)),
			liveTvProgram("B", start.plusMinutes(10), start.plusMinutes(40)),
		)
		val blocks = programs.programBlocks(listOf(start))

		blocks.map { it.program?.name } shouldContainExactly listOf("A", "B")
		blocks[0].slots shouldBe (0.33333334f plusOrMinus 0.000001f)
		blocks[1].slots shouldBe (0.6666667f plusOrMinus 0.000001f)
	}

	test("guide highlights the selected short program inside the half-hour viewport") {
		val start = LocalDateTime.of(2026, 9, 11, 18, 0)
		val programs = listOf(
			liveTvProgram("A", start, start.plusMinutes(10)),
			liveTvProgram("B", start.plusMinutes(10), start.plusMinutes(30)),
		)

		programs.programBlocks(listOf(start), start.plusMinutes(10))
			.filter { it.includesGuideTime }.map { it.program?.name } shouldContainExactly listOf("B")
	}

	test("cached guide blocks can update selection without rebuilding geometry") {
		val start = LocalDateTime.of(2026, 9, 11, 18, 0)
		val programs = listOf(
			liveTvProgram("A", start, start.plusMinutes(10)),
			liveTvProgram("B", start.plusMinutes(10), start.plusMinutes(30)),
		)
		val blocks = programs.programBlocks(listOf(start), selectedTime = null)

		blocks.filter { it.includes(start.plusMinutes(10)) }
			.map { it.program?.name } shouldContainExactly listOf("B")
	}

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

	test("selecting a trailing gap does not offer recording the final program") {
		val start = LocalDateTime.of(2026, 7, 13, 18, 0)
		val first = liveTvProgram("First", start, start.plusMinutes(30))
		val last = liveTvProgram("Last", start.plusMinutes(30), start.plusMinutes(60))

		listOf(last, first).programBlocks(listOf(start.plusMinutes(60))).map { it.program?.name } shouldContainExactly listOf(null)
		listOf(last, first).programAt(start.plusMinutes(60)) shouldBe null
	}

	test("guide range keeps the final short program selectable and excludes the exact end") {
		val start = LocalDateTime.of(2026, 7, 13, 18, 7)
		val program = liveTvProgram("Program", start, LocalDateTime.of(2026, 7, 13, 19, 0))

		mapOf(UUID.randomUUID() to listOf(program)).guideTimeRange() shouldBe
			(LocalDateTime.of(2026, 7, 13, 18, 0) to LocalDateTime.of(2026, 7, 13, 19, 0).minusNanos(1))
	}
})

private fun liveTvProgram(name: String, start: LocalDateTime, end: LocalDateTime) = BaseItemDto(
	id = UUID.randomUUID(),
	type = BaseItemKind.LIVE_TV_PROGRAM,
	name = name,
	startDate = start,
	endDate = end,
)
