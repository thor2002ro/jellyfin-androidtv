package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDateTime
import java.util.UUID

class HomeFragmentRecentlyReleasedRow(
	private val userRepository: UserRepository,
	private val userViews: Collection<BaseItemDto>,
	private val itemLimit: Int,
) : HomeFragmentRow, KoinComponent {
	private val userPreferences by inject<UserPreferences>()

	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		val latestItemsExcludes = userRepository.currentUser.value?.configuration?.latestItemsExcludes.orEmpty()
		val preferSeriesThumbnails = userPreferences[UserPreferences.seriesThumbnailsEnabled]

		userViews
			.filterNot { item -> item.collectionType in EXCLUDED_COLLECTION_TYPES || item.id in latestItemsExcludes }
			.map { item ->
				val request = createHomeRecentlyReleasedRequest(
					parentId = item.id,
					collectionType = item.collectionType,
					itemLimit = itemLimit,
				)
				val title = context.getString(R.string.lbl_recently_released_in, item.name)
				val row = BrowseRowDef(
					header = title,
					query = request,
					chunkSize = 0,
					preferParentThumb = preferSeriesThumbnails,
					staticHeight = true,
					changeTriggers = arrayOf(ChangeTriggerType.LibraryUpdated),
				)
				HomeFragmentBrowseRowDefRow(row)
			}.forEach { row ->
				row.addToRowsAdapter(context, cardPresenter, rowsAdapter)
			}
	}

	companion object {
		private val EXCLUDED_COLLECTION_TYPES = arrayOf(
			CollectionType.PLAYLISTS,
			CollectionType.LIVETV,
			CollectionType.BOXSETS,
			CollectionType.BOOKS,
		)
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
	maxPremiereDate = now,
	isUnaired = false,
	sortBy = listOf(
		ItemSortBy.PREMIERE_DATE,
		ItemSortBy.SERIES_SORT_NAME,
		ItemSortBy.AIRED_EPISODE_ORDER,
	),
	sortOrder = listOf(
		SortOrder.DESCENDING,
		SortOrder.ASCENDING,
		SortOrder.DESCENDING,
	),
)
