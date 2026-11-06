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
})

private class NoIdRowItem : BaseRowItem(BaseRowType.BaseItem)
