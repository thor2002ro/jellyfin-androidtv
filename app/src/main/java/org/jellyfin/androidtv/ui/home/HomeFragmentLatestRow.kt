package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDateTime
import java.util.UUID

class HomeFragmentLatestRow(
	private val userRepository: UserRepository,
	private val userViews: Collection<BaseItemDto>,
	private val itemLimit: Int,
	private val recentlyReleased: Boolean = false,
) : HomeFragmentRow, KoinComponent {
	private val userSettingPreferences by inject<UserSettingPreferences>()
	override fun addToRowsAdapter(
		context: Context,
		cardPresenter: CardPresenter,
		rowsAdapter: MutableObjectAdapter<Row>,
		rowOrder: Double,
	) {
		// Get configuration (to find excluded items)
		val configuration = userRepository.currentUser.value?.configuration

		// Create a list of views to include
		val latestItemsExcludes = configuration?.latestItemsExcludes.orEmpty()
		userViews
			.filterNot { item -> item.collectionType in EXCLUDED_COLLECTION_TYPES || item.id in latestItemsExcludes }
			.map { item ->
				val row = if (recentlyReleased) {
					BrowseRowDef(
						header = context.getString(R.string.lbl_recently_released_in, item.name),
						query = createHomeRecentlyReleasedRequest(item.id, item.collectionType, itemLimit),
						chunkSize = 0,
						preferParentThumb = userSettingPreferences[UserSettingPreferences.seriesThumbnailsEnabled],
						staticHeight = true,
						changeTriggers = arrayOf(ChangeTriggerType.LibraryUpdated),
					)
				} else {
					val request = GetLatestMediaRequest(
						fields = latestMediaFields(item.collectionType),
						imageTypeLimit = 1,
						parentId = item.id,
						groupItems = true,
						limit = effectiveHomeRowItemLimit(itemLimit, ITEM_LIMIT),
					)
					BrowseRowDef(context.getString(R.string.lbl_latest_in, item.name), request, arrayOf(ChangeTriggerType.LibraryUpdated))
				}
				HomeFragmentBrowseRowDefRow(row, showTitleYears = recentlyReleased)
			}.forEachIndexed { index, row ->
				// Add row to adapter
				row.addToRowsAdapter(context, cardPresenter, rowsAdapter, rowOrder + index.toDouble() / (userViews.size + 1))
			}
	}

	companion object {
		// Collections excluded from latest row based on app support and common sense
		private val EXCLUDED_COLLECTION_TYPES = arrayOf(
			CollectionType.PLAYLISTS,
			CollectionType.LIVETV,
			CollectionType.BOXSETS,
			CollectionType.BOOKS,
		)

		// Maximum amount of items loaded for a row
		private const val ITEM_LIMIT = 50
	}
}

internal fun createHomeRecentlyReleasedRequest(
	parentId: UUID,
	collectionType: CollectionType?,
	itemLimit: Int,
	now: LocalDateTime = LocalDateTime.now(),
) = GetItemsRequest(
	parentId = parentId,
	fields = latestMediaFields(collectionType),
	startIndex = 0,
	recursive = true,
	imageTypeLimit = 1,
	limit = effectiveHomeRowItemLimit(itemLimit, maximum = 50),
	enableTotalRecordCount = false,
	maxPremiereDate = now,
	isUnaired = false,
	sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SERIES_SORT_NAME, ItemSortBy.AIRED_EPISODE_ORDER),
	sortOrder = listOf(SortOrder.DESCENDING, SortOrder.ASCENDING, SortOrder.DESCENDING),
)

internal fun latestMediaFields(collectionType: CollectionType?) = when (collectionType) {
	CollectionType.MOVIES,
	CollectionType.TVSHOWS -> ItemRepository.streamBadgeFields

	else -> ItemRepository.browseFields
}
