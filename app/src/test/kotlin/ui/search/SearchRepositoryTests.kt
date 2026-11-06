package org.jellyfin.androidtv.ui.search

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.request.GetItemsRequest

class SearchRepositoryTests : FunSpec({
	test("search omits detail metadata while preserving video badges and video filtering") {
		val api = mockk<ApiClient>(relaxed = true)
		val outgoing = slot<GetItemsRequest>()
		coEvery { api.libraryApi.getItems(capture(outgoing)) } returns mockk {
			every { content } returns BaseItemDtoQueryResult(items = emptyList(), totalRecordCount = 0, startIndex = 0)
		}
		val repository = SearchRepositoryImpl(api)
		for (kind in listOf(BaseItemKind.MOVIE, BaseItemKind.VIDEO, BaseItemKind.AUDIO, BaseItemKind.PERSON, BaseItemKind.LIVE_TV_CHANNEL)) {
			repository.search("query", setOf(kind)).isSuccess shouldBe true
			val request = outgoing.captured
			request.limit shouldBe 25
			request.fields.orEmpty().contains(ItemFields.CHAPTERS) shouldBe false
			request.fields.orEmpty().contains(ItemFields.TRICKPLAY) shouldBe false
			request.fields.orEmpty().contains(ItemFields.OVERVIEW) shouldBe true
			val hasBadges = kind == BaseItemKind.MOVIE || kind == BaseItemKind.VIDEO
			request.fields.orEmpty().contains(ItemFields.MEDIA_SOURCES) shouldBe hasBadges
			request.fields.orEmpty().contains(ItemFields.MEDIA_STREAMS) shouldBe hasBadges
			if (kind == BaseItemKind.LIVE_TV_CHANNEL) request.fields.orEmpty().contains(ItemFields.CHANNEL_INFO) shouldBe true
			if (kind == BaseItemKind.VIDEO) {
				request.mediaTypes shouldBe setOf(MediaType.VIDEO)
				request.includeItemTypes shouldBe null
				request.excludeItemTypes shouldBe setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.TV_CHANNEL)
			}
		}
		repository.search("query", setOf(BaseItemKind.MOVIE, BaseItemKind.LIVE_TV_CHANNEL)).isSuccess shouldBe true
		outgoing.captured.fields.orEmpty().containsAll(setOf(ItemFields.CHANNEL_INFO, ItemFields.MEDIA_SOURCES, ItemFields.MEDIA_STREAMS)) shouldBe true
	}
})
