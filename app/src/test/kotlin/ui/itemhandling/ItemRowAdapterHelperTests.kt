package org.jellyfin.androidtv.ui.itemhandling

import android.content.Context
import androidx.leanback.widget.Presenter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.jellyfin.androidtv.data.model.FilterOptions
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
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

	test("changing library sorting invalidates pages from the previous ordering") {
		val adapter = itemAdapter()
		adapter.totalItems = 160
		adapter.itemsLoaded = 80

		adapter.setSorting(ItemSortBy.DATE_CREATED, SortOrder.DESCENDING)

		adapter.itemsLoaded shouldBe 0
		adapter.totalItems shouldBe 0
	}

	test("changing the alphabet filter invalidates pages from the unfiltered query") {
		val adapter = itemAdapter()
		adapter.totalItems = 160
		adapter.itemsLoaded = 80

		adapter.setStartLetter("T")

		adapter.itemsLoaded shouldBe 0
		adapter.totalItems shouldBe 0
	}

	test("full refresh replaces stale pages from the previous result set") {
		val staleItems = List(4) { index -> "Stale $index" }
		val refreshedItems = listOf("New 1", "New 2")

		val merged = mergeRetrievedItems(
			existingItems = staleItems,
			itemsLoaded = 0,
			newItems = refreshedItems,
		)

		merged shouldBe refreshedItems
	}

	test("next page appends after every item already loaded") {
		val firstPage = listOf("Item 1", "Item 2")
		val secondPage = listOf("Item 3", "Item 4")

		val merged = mergeRetrievedItems(
			existingItems = firstPage,
			itemsLoaded = firstPage.size,
			newItems = secondPage,
		)

		merged shouldBe firstPage + secondPage
	}

	test("next page removes exact duplicates returned by Jellyfin") {
		val firstId = UUID.randomUUID()
		val duplicateId = UUID.randomUUID()
		val lastId = UUID.randomUUID()
		val firstPage = listOf(
			BaseItemDtoBaseRowItem(BaseItemDto(id = firstId, name = "Item 1", type = BaseItemKind.MOVIE)),
			BaseItemDtoBaseRowItem(BaseItemDto(id = duplicateId, name = "Item 2", type = BaseItemKind.MOVIE)),
		)
		val secondPage = listOf(
			BaseItemDtoBaseRowItem(BaseItemDto(id = duplicateId, name = "Item 2 updated", type = BaseItemKind.MOVIE)),
			BaseItemDtoBaseRowItem(BaseItemDto(id = lastId, name = "Item 3", type = BaseItemKind.MOVIE)),
		)

		val merged = mergeRetrievedItems(
			existingItems = firstPage,
			itemsLoaded = firstPage.size,
			newItems = secondPage,
			identity = BaseRowItem::itemId,
		)

		merged.map(BaseRowItem::itemId) shouldBe listOf(firstId, duplicateId, lastId)
	}

	test("Jellyfin page offset counts consumed records instead of displayed duplicates") {
		val adapter = itemAdapter()
		adapter.totalItems = 42

		adapter.recordItemsRetrieved(startIndex = 40, count = 2)
		adapter.itemsLoaded = 1

		adapter.itemsLoaded shouldBe 1
		adapter.itemsRetrieved shouldBe 42
	}

	test("query results from before a sort change are rejected") {
		val adapter = itemAdapter()
		val oldQueryVersion = adapter.currentQueryVersion()

		adapter.setSorting(ItemSortBy.DATE_CREATED, SortOrder.DESCENDING)
		var resultApplied = false
		val applied = adapter.applyIfQueryCurrent(oldQueryVersion) {
			resultApplied = true
		}

		applied shouldBe false
		resultApplied shouldBe false
	}

	test("changing away from alphabet sorting invalidates the query once") {
		val adapter = itemAdapter()
		adapter.setStartLetter("T")
		val previousVersion = adapter.currentQueryVersion()

		adapter.setSorting(ItemSortBy.DATE_CREATED, SortOrder.DESCENDING)

		adapter.currentQueryVersion() shouldBe previousVersion + 1
	}

	test("filters invalidate only when their query values change") {
		val adapter = itemAdapter()
		adapter.setFilters(FilterOptions())
		val initialVersion = adapter.currentQueryVersion()

		adapter.setFilters(FilterOptions())
		adapter.currentQueryVersion() shouldBe initialVersion

		adapter.setFilters(FilterOptions(favoriteOnly = true))
		adapter.currentQueryVersion() shouldBe initialVersion + 1
	}

	test("query changes reset the Jellyfin page offset") {
		val adapter = itemAdapter()
		adapter.recordItemsRetrieved(startIndex = 0, count = 1)

		adapter.setStartLetter("T")

		adapter.itemsRetrieved shouldBe 0
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

private fun itemAdapter() = ItemRowAdapter(
	context = mockk<Context>(),
	query = GetItemsRequest(),
	chunkSize = 80,
	preferParentThumb = false,
	presenter = mockk<Presenter>(),
	parent = null,
)
