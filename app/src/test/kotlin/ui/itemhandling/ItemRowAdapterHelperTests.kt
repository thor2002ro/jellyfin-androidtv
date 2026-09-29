package org.jellyfin.androidtv.ui.itemhandling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
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
})

private class NoIdRowItem : BaseRowItem(BaseRowType.BaseItem)
