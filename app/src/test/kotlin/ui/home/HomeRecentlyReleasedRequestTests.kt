package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.time.LocalDateTime
import java.util.UUID

class HomeRecentlyReleasedRequestTests : FunSpec({
	test("recently released rows use release dates and exclude future media") {
		val parentId = UUID.randomUUID()
		val now = LocalDateTime.of(2026, 9, 19, 20, 0)
		val request = createHomeRecentlyReleasedRequest(
			parentId = parentId,
			collectionType = CollectionType.MOVIES,
			itemLimit = 100,
			now = now,
		)

		request.parentId shouldBe parentId
		request.startIndex shouldBe 0
		request.recursive shouldBe true
		request.limit shouldBe 50
		request.imageTypeLimit shouldBe 1
		request.maxPremiereDate shouldBe now
		request.isUnaired shouldBe false
		request.sortBy shouldBe listOf(
			ItemSortBy.PREMIERE_DATE,
			ItemSortBy.SERIES_SORT_NAME,
			ItemSortBy.AIRED_EPISODE_ORDER,
		)
		request.sortOrder shouldBe listOf(
			SortOrder.DESCENDING,
			SortOrder.ASCENDING,
			SortOrder.DESCENDING,
		)
		request.fields shouldBe ItemRepository.streamBadgeFields
	}
})
