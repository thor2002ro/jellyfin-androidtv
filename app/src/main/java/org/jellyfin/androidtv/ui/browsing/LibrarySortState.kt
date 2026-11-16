package org.jellyfin.androidtv.ui.browsing

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

data class LibrarySortState(
	val field: ItemSortBy,
	val direction: SortOrder,
)

data class LibrarySortOption(
	val field: ItemSortBy,
	val naturalDirection: SortOrder,
)

fun selectSort(current: LibrarySortState, selected: LibrarySortOption): LibrarySortState =
	if (current.field == selected.field) current.copy(direction = current.direction.reversed())
	else LibrarySortState(selected.field, selected.naturalDirection)

fun SortOrder.reversed(): SortOrder = when (this) {
	SortOrder.ASCENDING -> SortOrder.DESCENDING
	SortOrder.DESCENDING -> SortOrder.ASCENDING
}

val SortOrder.menuArrow: String
	get() = when (this) {
		SortOrder.ASCENDING -> "↑"
		SortOrder.DESCENDING -> "↓"
	}

internal data class BrowseSortOption(val name: String, val value: ItemSortBy, val order: SortOrder)

internal fun browseSortOptions(context: Context, collectionType: CollectionType?): Map<Int, BrowseSortOption> {
	fun option(label: Int, field: ItemSortBy, order: SortOrder) = BrowseSortOption(context.getString(label), field, order)
	val isTvShows = collectionType == CollectionType.TVSHOWS
	return buildMap {
		put(0, option(R.string.lbl_name, ItemSortBy.SORT_NAME, SortOrder.ASCENDING))
		put(1, option(if (isTvShows) R.string.lbl_date_show_added else R.string.lbl_date_added, ItemSortBy.DATE_CREATED, SortOrder.DESCENDING))
		put(2, option(R.string.lbl_premier_date, ItemSortBy.PREMIERE_DATE, SortOrder.DESCENDING))
		put(3, option(R.string.lbl_rating, ItemSortBy.OFFICIAL_RATING, SortOrder.ASCENDING))
		put(4, option(R.string.lbl_community_rating, ItemSortBy.COMMUNITY_RATING, SortOrder.DESCENDING))
		put(5, option(R.string.lbl_critic_rating, ItemSortBy.CRITIC_RATING, SortOrder.DESCENDING))
		put(6, option(R.string.lbl_last_played, if (isTvShows) ItemSortBy.SERIES_DATE_PLAYED else ItemSortBy.DATE_PLAYED, SortOrder.DESCENDING))
		if (isTvShows) put(7, option(R.string.lbl_date_episode_added, ItemSortBy.DATE_LAST_CONTENT_ADDED, SortOrder.DESCENDING))
		else if (collectionType == CollectionType.MOVIES) put(7, option(R.string.lbl_runtime, ItemSortBy.RUNTIME, SortOrder.ASCENDING))
	}
}
