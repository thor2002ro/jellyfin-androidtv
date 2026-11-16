package org.jellyfin.androidtv.ui.browsing

import org.jellyfin.androidtv.data.model.FilterOptions

internal fun nextSelectionAfterQueryChange(totalItems: Int) = if (totalItems > 0) 0 else -1

internal fun shouldRetrieveGrid(adapterRebuilt: Boolean, queryChangePending: Boolean, itemsLoaded: Int) =
	adapterRebuilt || queryChangePending || itemsLoaded <= 0

internal fun shouldKeepToolbarFocusable(itemsLoaded: Int, filtersActive: Boolean) = itemsLoaded == 0 && filtersActive

internal fun shouldRemoveFilteredItem(filters: FilterOptions, favorite: Boolean, played: Boolean) =
	(filters.isFavoriteOnly() && !favorite) ||
		(filters.isUnwatchedOnly() && played) ||
		(filters.isWatchedOnly() && !played)

internal fun shouldApplyLibraryFilters(current: FilterOptions?, next: FilterOptions?) = current != next
