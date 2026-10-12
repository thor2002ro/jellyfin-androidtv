package org.jellyfin.androidtv.ui.browsing

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
