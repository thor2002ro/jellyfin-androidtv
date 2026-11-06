package org.jellyfin.androidtv.ui.browsing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetGenresRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID

internal data class LibraryGenre(val name: String, val ids: Set<UUID>) {
	val id get() = ids.first()
}

internal data class LibraryGenreDetails(val itemCount: Int?, val images: List<JellyfinImage>)

internal suspend fun loadLibraryGenreDetails(
	api: ApiClient,
	parentId: UUID,
	includeType: BaseItemKind?,
	genre: LibraryGenre,
): LibraryGenreDetails = withContext(Dispatchers.IO) {
	val result = api.libraryApi.getItems(
		GetItemsRequest(
			parentId = parentId,
			includeItemTypes = includeType?.let(::setOf),
			genreIds = genre.ids,
			recursive = true,
			limit = 12,
			imageTypeLimit = 1,
			enableTotalRecordCount = true,
		),
	).content
	val images = result.items.asSequence()
		.mapNotNull { item ->
			item.itemBackdropImages.firstOrNull() ?: item.parentBackdropImages.firstOrNull()
				?: item.itemImages[ImageType.THUMB] ?: item.itemImages[ImageType.PRIMARY]
		}
		.distinctBy(JellyfinImage::item)
		.take(4)
		.toList()
	LibraryGenreDetails(result.totalRecordCount, images)
}

internal suspend fun loadLibraryGenres(api: ApiClient, parentId: UUID, includeType: BaseItemKind?): List<LibraryGenre> =
	withContext(Dispatchers.IO) {
		val genres = mutableListOf<BaseItemDto>()
		do {
			val page = api.genreApi.getGenres(
				GetGenresRequest(
					parentId = parentId,
					includeItemTypes = includeType?.let(::setOf),
					startIndex = genres.size,
					limit = 100,
					sortBy = setOf(ItemSortBy.SORT_NAME),
					sortOrder = setOf(SortOrder.ASCENDING),
					enableTotalRecordCount = true,
				),
			).content
			if (page.items.isEmpty()) break
			genres.addAll(page.items)
		} while (genres.size < page.totalRecordCount)
		val uniqueGenres = genres.filter { !it.name.isNullOrBlank() }.distinctBy(BaseItemDto::id)
		uniqueGenres.groupEquivalentGenres().map { choice ->
			LibraryGenre(choice.name, choice.ids)
		}
	}
