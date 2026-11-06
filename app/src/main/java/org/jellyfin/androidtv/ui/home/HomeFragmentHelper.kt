package org.jellyfin.androidtv.ui.home

import android.content.Context
import android.view.View
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItemSelectAction
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetRecommendedProgramsRequest
import org.jellyfin.sdk.model.api.request.GetRecordingsRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import java.time.LocalDateTime

private const val ITEM_LIMIT_NEXT_UP = 50

class HomeFragmentHelper(
	private val context: Context,
	private val userRepository: UserRepository,
	private val itemLimit: Int,
	private val includeNextUpRewatching: Boolean,
) {
	fun loadRecentlyAdded(userViews: Collection<BaseItemDto>): HomeFragmentRow {
		return HomeFragmentLatestRow(userRepository, userViews, itemLimit)
	}

	fun loadRecentlyReleased(userViews: Collection<BaseItemDto>): HomeFragmentRow {
		return HomeFragmentRecentlyReleasedRow(userRepository, userViews, itemLimit)
	}

	fun loadFavoriteVideos(): HomeFragmentRow {
		return HomeFragmentFavoriteVideosRow(itemLimit)
	}

	fun loadResume(title: String, includeMediaTypes: Collection<MediaType>): HomeFragmentRow {
		val query = GetResumeItemsRequest(
			limit = effectiveHomeRowItemLimit(itemLimit, ITEM_LIMIT_RESUME),
			fields = if (MediaType.VIDEO in includeMediaTypes) ItemRepository.streamBadgeFields else ItemRepository.browseFields,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			mediaTypes = includeMediaTypes,
			excludeItemTypes = setOf(BaseItemKind.AUDIO_BOOK),
		)

		return HomeFragmentBrowseRowDefRow(BrowseRowDef(title, query, 0, false, true, arrayOf(ChangeTriggerType.TvPlayback, ChangeTriggerType.MoviePlayback)))
	}

	fun loadResumeVideo(combineWithNextUp: Boolean = false): HomeFragmentRow {
		if (!combineWithNextUp) {
			return loadResume(context.getString(R.string.lbl_continue_watching), listOf(MediaType.VIDEO))
		}

		val query = createHomeNextUpRequest(
			itemLimit = itemLimit,
			includeRewatching = includeNextUpRewatching,
			includeResumable = true,
		)
		return HomeFragmentBrowseRowDefRow(
			BrowseRowDef(
				context.getString(R.string.home_combined_continue_watching_next_up),
				query,
				arrayOf(ChangeTriggerType.TvPlayback, ChangeTriggerType.MoviePlayback),
			)
		)
	}

	fun loadResumeAudio(): HomeFragmentRow {
		return loadResume(context.getString(R.string.continue_listening), listOf(MediaType.AUDIO))
	}

	fun loadLatestLiveTvRecordings(): HomeFragmentRow {
		val query = GetRecordingsRequest(
			fields = ItemRepository.itemFields,
			enableImages = true,
			limit = effectiveHomeRowItemLimit(itemLimit, ITEM_LIMIT_RECORDINGS)
		)

		return HomeFragmentBrowseRowDefRow(BrowseRowDef(context.getString(R.string.lbl_recordings), query))
	}

	fun loadNextUp(): HomeFragmentRow {
		val query = createHomeNextUpRequest(
			itemLimit = itemLimit,
			includeRewatching = includeNextUpRewatching,
		)

		return HomeFragmentBrowseRowDefRow(BrowseRowDef(context.getString(R.string.lbl_next_up), query, arrayOf(ChangeTriggerType.TvPlayback)))
	}

	fun loadOnNow(onLongClick: ((item: Any?, view: View) -> Boolean)? = null): HomeFragmentRow {
		val query = GetRecommendedProgramsRequest(
			isAiring = true,
			fields = ItemRepository.itemFields,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			limit = effectiveHomeRowItemLimit(itemLimit, ITEM_LIMIT_ON_NOW)
		)

		return HomeFragmentBrowseRowDefRow(
			BrowseRowDef(context.getString(R.string.lbl_on_now), query, true, BaseRowItemSelectAction.Play),
			onLongClick,
		)
	}

	companion object {
		// Maximum amount of items loaded for a row
		private const val ITEM_LIMIT_RESUME = 50
		private const val ITEM_LIMIT_RECORDINGS = 40
		private const val ITEM_LIMIT_ON_NOW = 20
	}
}

internal fun effectiveHomeRowItemLimit(configuredLimit: Int, maximum: Int) =
	configuredLimit.coerceIn(minimumValue = 5, maximumValue = maximum)

internal fun createHomeNextUpRequest(
	itemLimit: Int,
	includeRewatching: Boolean,
	includeResumable: Boolean = false,
) = GetNextUpRequest(
	imageTypeLimit = 1,
	limit = effectiveHomeRowItemLimit(itemLimit, maximum = ITEM_LIMIT_NEXT_UP),
	enableResumable = includeResumable,
	enableRewatching = includeRewatching,
	fields = ItemRepository.streamBadgeFields,
)

internal fun GetNextUpRequest.withHomeNextUpCutoff(
	maxDays: Int,
	now: LocalDateTime = LocalDateTime.now(),
) = copy(nextUpDateCutoff = maxDays.takeIf { it > 0 }?.let { now.minusDays(it.toLong()) })
