package org.jellyfin.androidtv.ui.itemhandling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import java.util.UUID

class BaseItemDtoBaseRowItemTests : FunSpec({
	test("stream badge media is separate from the card item") {
		val item = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.MOVIE,
		)
		val badgeSources = emptyList<MediaSourceInfo>()
		val rowItem = BaseItemDtoBaseRowItem(
			item = item,
			streamBadgeMediaSources = badgeSources,
		)

		rowItem.baseItem shouldBe item
		rowItem.baseItem?.mediaSources shouldBe null
		rowItem.streamBadgeItem?.mediaSources shouldBe badgeSources
		(rowItem.streamBadgeItem === rowItem.streamBadgeItem) shouldBe true
	}

	test("stream badge media changes row equality") {
		val item = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.MOVIE,
		)

		BaseItemDtoBaseRowItem(item) shouldNotBe BaseItemDtoBaseRowItem(
			item = item,
			streamBadgeMediaSources = emptyList(),
		)
	}
})
