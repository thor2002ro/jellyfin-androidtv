package org.jellyfin.androidtv.ui.favorites

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

class FavoritesScreenTests : FunSpec({
	test("favorites screen includes every supported category") {
		favoriteCategories.map { category -> category.titleRes to category.itemTypes } shouldBe listOf(
			R.string.lbl_movies to listOf(BaseItemKind.MOVIE),
			R.string.lbl_series to listOf(BaseItemKind.SERIES),
			R.string.lbl_episodes to listOf(BaseItemKind.EPISODE),
			R.string.lbl_videos to listOf(BaseItemKind.VIDEO),
			R.string.lbl_favorite_channels to listOf(BaseItemKind.TV_CHANNEL),
			R.string.lbl_playlists to listOf(BaseItemKind.PLAYLIST),
			R.string.lbl_artists to listOf(BaseItemKind.MUSIC_ARTIST),
			R.string.lbl_albums to listOf(BaseItemKind.MUSIC_ALBUM),
			R.string.lbl_songs to listOf(BaseItemKind.AUDIO),
			R.string.photo_albums to listOf(BaseItemKind.PHOTO_ALBUM),
			R.string.photos to listOf(BaseItemKind.PHOTO),
			R.string.lbl_collections to listOf(BaseItemKind.BOX_SET),
			R.string.lbl_people to listOf(BaseItemKind.PERSON),
		)
	}

	test("favorites category request supports adapter paging") {
		val request = createFavoritesRequest(listOf(BaseItemKind.MOVIE))

		request.startIndex shouldBe null
		request.limit shouldBe null
		request.recursive shouldBe true
		request.imageTypeLimit shouldBe 1
		request.includeItemTypes shouldBe listOf(BaseItemKind.MOVIE)
		request.filters shouldBe setOf(ItemFilter.IS_FAVORITE)
		request.sortBy shouldBe listOf(ItemSortBy.SORT_NAME)
		request.sortOrder shouldBe listOf(SortOrder.ASCENDING)
		request.enableTotalRecordCount shouldBe true
		request.fields shouldBe ItemRepository.itemFields
	}

	test("favorites cards use the compact wide-row height") {
		createFavoritesCardPresenter().staticHeight shouldBe 120
	}

	test("favorites cards hide redundant favorite indicators") {
		createFavoritesCardPresenter().showFavoriteIndicator shouldBe false
	}
})
