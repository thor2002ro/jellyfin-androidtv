package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest

class HomeFragmentFavoriteVideosRow(
	private val itemLimit: Int,
) : HomeFragmentRow {
	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		val row = BrowseRowDef(
			header = context.getString(R.string.lbl_favorites),
			query = createHomeFavoriteVideosRequest(itemLimit),
			chunkSize = 0,
			preferParentThumb = false,
			staticHeight = true,
			changeTriggers = arrayOf(ChangeTriggerType.LibraryUpdated, ChangeTriggerType.FavoriteUpdate),
		)
		HomeFragmentBrowseRowDefRow(row).addToRowsAdapter(context, cardPresenter, rowsAdapter)
	}
}

internal fun createHomeFavoriteVideosRequest(itemLimit: Int) = GetItemsRequest(
	fields = ItemRepository.streamBadgeFields,
	startIndex = 0,
	limit = effectiveHomeRowItemLimit(itemLimit, maximum = 50),
	recursive = true,
	imageTypeLimit = 1,
	includeItemTypes = listOf(
		BaseItemKind.MOVIE,
		BaseItemKind.SERIES,
		BaseItemKind.VIDEO,
	),
	filters = setOf(ItemFilter.IS_FAVORITE),
	sortBy = listOf(ItemSortBy.SORT_NAME),
	sortOrder = listOf(SortOrder.ASCENDING),
)
