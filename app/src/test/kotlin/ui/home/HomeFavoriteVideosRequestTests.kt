package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

class HomeFavoriteVideosRequestTests : FunSpec({
	test("favorite videos row requests supported video types with the home limit") {
		val request = createHomeFavoriteVideosRequest(itemLimit = 100)

		request.startIndex shouldBe 0
		request.limit shouldBe 50
		request.recursive shouldBe true
		request.imageTypeLimit shouldBe 1
		request.includeItemTypes shouldBe listOf(
			BaseItemKind.MOVIE,
			BaseItemKind.SERIES,
			BaseItemKind.VIDEO,
		)
		request.filters shouldBe setOf(ItemFilter.IS_FAVORITE)
		request.sortBy shouldBe listOf(ItemSortBy.SORT_NAME)
		request.sortOrder shouldBe listOf(SortOrder.ASCENDING)
		request.fields shouldBe ItemRepository.streamBadgeFields
	}
})
