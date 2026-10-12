package org.jellyfin.androidtv.ui.itemhandling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID

class ItemRowAdapterHelperTests : FunSpec({
	listOf("timerId", "seriesTimerId").forEach { field ->
		test("live TV row refresh detects $field changes after recording actions") {
			val program = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.LIVE_TV_PROGRAM, name = "News")
			val recorded = if (field == "timerId") program.copy(timerId = "recording") else program.copy(seriesTimerId = "series")

			BaseItemDtoBaseRowItem(recorded).liveTvProgramSignature() shouldNotBe
				BaseItemDtoBaseRowItem(program).liveTvProgramSignature()
		}
	}

	test("adapter diff keeps the same media item identity when content changes") {
		val itemId = UUID.randomUUID()

		areAdapterItemsTheSame(
			BaseItemDtoBaseRowItem(BaseItemDto(id = itemId, name = "Before", type = BaseItemKind.SERIES)),
			BaseItemDtoBaseRowItem(BaseItemDto(id = itemId, name = "After", type = BaseItemKind.SERIES)),
		) shouldBe true
	}

	test("adapter diff does not merge row items without ids") {
		areAdapterItemsTheSame(NoIdRowItem(), NoIdRowItem()) shouldBe false
	}

	test("only resumable item queries request remaining time badges") {
		GetItemsRequest(filters = setOf(ItemFilter.IS_RESUMABLE)).showsRemainingTimeBadges() shouldBe true
		GetItemsRequest().showsRemainingTimeBadges() shouldBe false
	}

	test("resume signature changes when runtime changes") {
		val itemId = UUID.randomUUID()

		BaseItemDtoBaseRowItem(
			BaseItemDto(id = itemId, type = BaseItemKind.MOVIE, runTimeTicks = 1),
		).resumeSignature() shouldNotBe BaseItemDtoBaseRowItem(
			BaseItemDto(id = itemId, type = BaseItemKind.MOVIE, runTimeTicks = 2),
		).resumeSignature()
	}

	test("resume signature changes when remaining time badge marker changes") {
		val item = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.MOVIE,
		)

		BaseItemDtoBaseRowItem(item).resumeSignature() shouldNotBe ResumeItemBaseRowItem(
			item,
			preferParentThumb = false,
			staticHeight = true,
		).resumeSignature()
	}
	test("resume signature changes when server artwork changes") {
		val itemId = UUID.randomUUID()

		BaseItemDtoBaseRowItem(
			BaseItemDto(
				id = itemId,
				type = BaseItemKind.EPISODE,
				seriesThumbImageTag = "old-thumb",
			)
		).resumeSignature() shouldNotBe BaseItemDtoBaseRowItem(
			BaseItemDto(
				id = itemId,
				type = BaseItemKind.EPISODE,
				seriesThumbImageTag = "new-thumb",
			)
		).resumeSignature()
	}

	test("next up signature changes when server artwork changes") {
		val itemId = UUID.randomUUID()

		BaseItemDtoBaseRowItem(
			BaseItemDto(id = itemId, type = BaseItemKind.EPISODE, seriesPrimaryImageTag = "old-poster")
		).itemSignature() shouldNotBe BaseItemDtoBaseRowItem(
			BaseItemDto(id = itemId, type = BaseItemKind.EPISODE, seriesPrimaryImageTag = "new-poster")
		).itemSignature()
	}


	test("combined home items keep resume order, remove next up duplicates, and respect the limit") {
		val resumedMovie = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE)
		val resumedEpisode = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE)
		val nextEpisode = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE)

		combineContinueWatchingAndNextUpItems(
			resumeItems = listOf(resumedMovie, resumedEpisode),
			nextUpItems = listOf(resumedEpisode, nextEpisode),
			limit = 3,
		) shouldBe listOf(resumedMovie, resumedEpisode, nextEpisode)

		combineContinueWatchingAndNextUpItems(
			resumeItems = listOf(resumedMovie, resumedEpisode),
			nextUpItems = listOf(nextEpisode),
			limit = 2,
		) shouldBe listOf(resumedMovie, resumedEpisode)
	}

	test("combined home cards show remaining time only for resumable items") {
		val resumedMovie = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE)
		val nextEpisode = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE)

		val rowItems = combinedContinueWatchingAndNextUpRowItems(
			resumeItems = listOf(resumedMovie),
			nextUpItems = listOf(nextEpisode),
			limit = 2,
			preferParentThumb = true,
			staticHeight = true,
		)

		rowItems.map(BaseRowItem::showRemainingTimeBadge) shouldBe listOf(true, false)
		rowItems.map(BaseRowItem::preferParentThumb) shouldBe listOf(true, true)
	}

})

private class NoIdRowItem : BaseRowItem(BaseRowType.BaseItem)
