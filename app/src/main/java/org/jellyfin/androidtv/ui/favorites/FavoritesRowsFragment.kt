package org.jellyfin.androidtv.ui.favorites

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.browsing.BrowsingUtils
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.util.dimenDp
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.koin.android.ext.android.inject

private const val FAVORITES_PAGE_SIZE = 50
private const val FAVORITES_CARD_HEIGHT = 120

internal data class FavoriteCategory(
	@StringRes val titleRes: Int,
	val itemTypes: List<BaseItemKind>,
)

internal val favoriteCategories = listOf(
	FavoriteCategory(R.string.lbl_movies, listOf(BaseItemKind.MOVIE)),
	FavoriteCategory(R.string.lbl_series, listOf(BaseItemKind.SERIES)),
	FavoriteCategory(R.string.lbl_episodes, listOf(BaseItemKind.EPISODE)),
	FavoriteCategory(R.string.lbl_videos, listOf(BaseItemKind.VIDEO)),
	FavoriteCategory(R.string.lbl_favorite_channels, listOf(BaseItemKind.TV_CHANNEL)),
	FavoriteCategory(R.string.lbl_playlists, listOf(BaseItemKind.PLAYLIST)),
	FavoriteCategory(R.string.lbl_artists, listOf(BaseItemKind.MUSIC_ARTIST)),
	FavoriteCategory(R.string.lbl_albums, listOf(BaseItemKind.MUSIC_ALBUM)),
	FavoriteCategory(R.string.lbl_songs, listOf(BaseItemKind.AUDIO)),
	FavoriteCategory(R.string.photo_albums, listOf(BaseItemKind.PHOTO_ALBUM)),
	FavoriteCategory(R.string.photos, listOf(BaseItemKind.PHOTO)),
	FavoriteCategory(R.string.lbl_collections, listOf(BaseItemKind.BOX_SET)),
	FavoriteCategory(R.string.lbl_people, listOf(BaseItemKind.PERSON)),
)

internal fun createFavoritesRequest(itemTypes: Collection<BaseItemKind>) = GetItemsRequest(
	fields = ItemRepository.itemFields,
	recursive = true,
	imageTypeLimit = 1,
	includeItemTypes = itemTypes,
	filters = setOf(ItemFilter.IS_FAVORITE),
	sortBy = listOf(ItemSortBy.SORT_NAME),
	sortOrder = listOf(SortOrder.ASCENDING),
	enableTotalRecordCount = true,
)

internal fun createFavoritesCardPresenter() = CardPresenter(
	showInfo = true,
	imageType = ImageType.POSTER,
	staticHeight = FAVORITES_CARD_HEIGHT,
	uniformAspect = false,
	showFavoriteIndicator = false,
)

class FavoritesRowsFragment : RowsSupportFragment() {
	private val backgroundService by inject<BackgroundService>()
	private val itemLauncher by inject<ItemLauncher>()
	private val rowAdapters = mutableListOf<ItemRowAdapter>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val rowsAdapter = MutableObjectAdapter<Row>(PositionableListRowPresenter())
		adapter = rowsAdapter

		favoriteCategories.forEachIndexed { index, category ->
			val rowAdapter = if (category.itemTypes == listOf(BaseItemKind.TV_CHANNEL)) {
				ItemRowAdapter(
					requireContext(),
					BrowsingUtils.createLiveTVChannelsRequest(true),
					FAVORITES_PAGE_SIZE,
					CardPresenter(
						showInfo = false,
						imageType = ImageType.THUMB,
						staticHeight = requireContext().dimenDp(R.dimen.live_tv_card_height),
						uniformAspect = false,
						showFavoriteIndicator = false,
					),
					rowsAdapter,
				)
			} else {
				ItemRowAdapter(
					requireContext(),
					createFavoritesRequest(category.itemTypes),
					FAVORITES_PAGE_SIZE,
					false,
					true,
					createFavoritesCardPresenter(),
					rowsAdapter,
				)
			}
			rowAdapter.apply {
				setReRetrieveTriggers(arrayOf(ChangeTriggerType.LibraryUpdated, ChangeTriggerType.FavoriteUpdate))
			}
			val row = ListRow(HeaderItem(getString(category.titleRes)), rowAdapter)
			rowAdapter.setRow(row, index.toDouble())
			rowAdapters += rowAdapter
			rowsAdapter.add(row)
			rowAdapter.Retrieve()
		}

		onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
			val rowItem = item as? BaseRowItem ?: return@OnItemViewClickedListener
			val rowAdapter = (row as? ListRow)?.adapter as? ItemRowAdapter ?: return@OnItemViewClickedListener
			itemLauncher.launch(rowItem, rowAdapter, requireContext())
		}
		onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, row ->
			val rowItem = item as? BaseRowItem
			val rowAdapter = (row as? ListRow)?.adapter as? ItemRowAdapter
			if (rowItem != null && rowAdapter != null) {
				rowAdapter.loadMoreItemsIfNeeded(rowAdapter.indexOf(rowItem))
			}

			val baseItem = rowItem?.baseItem
			if (baseItem == null) backgroundService.clearBackgrounds()
			else backgroundService.setBackground(baseItem)
		}
	}

	override fun onResume() {
		super.onResume()
		rowAdapters.forEach(ItemRowAdapter::ReRetrieveIfNeeded)
	}
}
