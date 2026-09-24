package org.jellyfin.androidtv.ui.browsing

import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.constant.LiveTvOption
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItemSelectAction
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.livetv.LiveTvCardActionHandler
import org.jellyfin.androidtv.ui.livetv.liveTvActionButtons
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.LiveTvActionButtonPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.dimenDp
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.LocalDateTime

open class LiveTvLibraryFragment : EnhancedBrowseFragment() {
	private val userRepository by inject<UserRepository>()
	private val playbackHelper by inject<PlaybackHelper>()
	private val api by inject<ApiClient>()
	private val liveTvRowAdapters = mutableListOf<ItemRowAdapter>()
	private val liveTvActions by lazy { LiveTvCardActionHandler(this, api, playbackHelper) { _, _ -> refreshLiveTvRows() } }

	override fun onResume() {
		val wasJustLoaded = justLoaded
		super.onResume()
		if (wasJustLoaded) return

		Handler(Looper.getMainLooper()).postDelayed({ refreshLiveTvRows() }, LIVE_TV_ROWS_REFRESH_DELAY_MS)
	}

	override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		view.findViewById<android.widget.TextView>(R.id.summary).apply {
			setTextSize(TypedValue.COMPLEX_UNIT_SP, LIVE_TV_SUMMARY_TEXT_SIZE_SP)
			maxLines = LIVE_TV_SUMMARY_MAX_LINES
			layoutParams = layoutParams.apply {
				height = TypedValue.applyDimension(
					TypedValue.COMPLEX_UNIT_DIP,
					LIVE_TV_SUMMARY_HEIGHT_DP,
					resources.displayMetrics,
				).toInt()
			}
		}
	}

	override fun onPause() {
		liveTvActions.dismiss()
		super.onPause()
	}

	private fun refreshLiveTvRows(includePrograms: Boolean = true) {
		if (!isAdded) return

		if (mRowsAdapter == null) return
		for (adapter in liveTvRowAdapters) {
			if (adapter.queryType == QueryType.LiveTvChannel || (includePrograms && adapter.queryType == QueryType.LiveTvProgram)) {
				adapter.Retrieve()
			}
		}
	}

	private fun getSmartRowInsertIndex() = minOf(SMART_ROW_INSERT_INDEX, mRowsAdapter.size())

	override fun setupQueries(rowLoader: RowLoader) {
		showViews = true
		liveTvRowAdapters.clear()

		mRows.add(BrowseRowDef(
			getString(R.string.lbl_on_now),
			BrowsingUtils.createLiveTVOnNowRequest(),
			arrayOf(ChangeTriggerType.TvPlayback),
			true,
			BaseRowItemSelectAction.Play,
		))
		mRows.add(BrowseRowDef(
			getString(R.string.lbl_recently_played_channels),
			BrowsingUtils.createLiveTVRecentlyPlayedChannelsRequest(),
			arrayOf(ChangeTriggerType.TvPlayback),
		))
		mRows.add(BrowseRowDef(
			getString(R.string.lbl_favorite_channels),
			BrowsingUtils.createLiveTVChannelsRequest(true),
		))
		mRows.add(BrowseRowDef(
			getString(R.string.lbl_coming_up),
			BrowsingUtils.createLiveTVUpcomingRequest(),
		))

		rowLoader.loadRows(mRows)

		getLiveTvRecordingsAndTimers(
			callback = { recordings, timers ->
				if (!isAdded || mRowsAdapter == null) return@getLiveTvRecordingsAndTimers

				val nearTimers = timers.items
					.filter { timer -> timer.startDate?.isBefore(LocalDateTime.now().plusDays(1)) == true }
					.map(::getTimerProgramInfo)

				if (recordings.totalRecordCount > 0) {
					val past24 = LocalDateTime.now().minusDays(1)
					val pastWeek = LocalDateTime.now().minusWeeks(1)
					val dayItems = mutableListOf<BaseItemDto>()
					val weekItems = mutableListOf<BaseItemDto>()

					recordings.items.forEach { item ->
						val dateCreated = item.dateCreated ?: return@forEach
						when {
							dateCreated.isAfter(past24) -> dayItems.add(item)
							dateCreated.isAfter(pastWeek) -> weekItems.add(item)
						}
					}

					addStaticRow(getString(R.string.past_week), weekItems)
					addStaticRow(getString(R.string.scheduled_in_next_24_hours), nearTimers)
					addStaticRow(getString(R.string.past_24_hours), dayItems)
					addRecentRecordingsRow()
				} else {
					addStaticRow(getString(R.string.scheduled_in_next_24_hours), nearTimers)
				}
			},
			errorCallback = { exception ->
				Timber.e(exception, "Failed to get Live TV recordings / timers")
			}
		)
	}

	override fun onRowAdapterCreated(rowDef: BrowseRowDef, rowAdapter: ItemRowAdapter) {
		if (rowDef.queryType == QueryType.LiveTvChannel || rowDef.queryType == QueryType.LiveTvProgram) {
			liveTvRowAdapters.add(rowAdapter)
		}
	}

	private fun addStaticRow(header: String, items: List<BaseItemDto>) {
		if (items.isEmpty()) return

		val adapter = ItemRowAdapter(requireContext(), items, mCardPresenter, mRowsAdapter, true)
		val row = ListRow(HeaderItem(header), adapter)
		adapter.setRow(row, SMART_ROW_ORDER)
		adapter.Retrieve()
		mRowsAdapter.add(getSmartRowInsertIndex(), row)
	}

	private fun addRecentRecordingsRow() {
		val adapter = ItemRowAdapter(
			requireContext(),
			BrowsingUtils.createLiveTVRecordingsRequest(RECENT_RECORDINGS_LIMIT),
			RECENT_RECORDINGS_LIMIT,
			mCardPresenter,
			mRowsAdapter,
		)
		val row = ListRow(HeaderItem(getString(R.string.lbl_recent_recordings)), adapter)
		adapter.setRow(row)
		adapter.Retrieve()
		mRowsAdapter.add(row)
	}

	override fun addAdditionalRows(rowAdapter: MutableObjectAdapter<Row>) {
		val gridRowAdapter = ArrayObjectAdapter(LiveTvActionButtonPresenter(defaultGridButtonWidth, defaultGridButtonHeight)).apply {
			liveTvActionButtons(requireContext(), userRepository.currentUser.value).forEach(::add)
		}

		rowAdapter.add(0, ListRow(HeaderItem(0, getString(R.string.pref_live_tv_cat)), gridRowAdapter))
	}

	override fun getLiveTvCardPresenter() = CardPresenter(false, getDefaultCardImageType(), getDefaultCardHeight(), liveTvActions::onLongClick)

	override fun getDefaultCardImageType() = ImageType.THUMB

	override fun getDefaultCardHeight() = requireContext().dimenDp(R.dimen.live_tv_card_height)

	override fun getDefaultGridButtonWidth() = requireContext().dimenDp(R.dimen.live_tv_action_tile_width)

	override fun getDefaultGridButtonHeight() = requireContext().dimenDp(R.dimen.live_tv_action_tile_height)

	private companion object {
		const val LIVE_TV_SUMMARY_TEXT_SIZE_SP = 11f
		const val LIVE_TV_SUMMARY_HEIGHT_DP = 72f
		const val LIVE_TV_SUMMARY_MAX_LINES = 4
		const val SMART_ROW_INSERT_INDEX = 2
		const val SMART_ROW_ORDER = 0.5
		const val LIVE_TV_ROWS_REFRESH_DELAY_MS = 3_500L
		const val RECENT_RECORDINGS_LIMIT = 50
	}
}
