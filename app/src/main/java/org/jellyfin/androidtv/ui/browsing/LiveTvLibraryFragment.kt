package org.jellyfin.androidtv.ui.browsing

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RelativeLayout
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.constant.LiveTvOption
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.LiveProgramDetailPopup
import org.jellyfin.androidtv.ui.card.ChannelCardView
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItemSelectAction
import org.jellyfin.androidtv.ui.itemhandling.BaseRowType
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.livetv.LiveTvGuide
import org.jellyfin.androidtv.ui.presentation.ChannelCardPresenter
import org.jellyfin.androidtv.ui.presentation.GridButtonPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID

class LiveTvLibraryFragment : EnhancedBrowseFragment() {
	private val userRepository by inject<UserRepository>()
	private val playbackHelper by inject<PlaybackHelper>()
	private val api by inject<ApiClient>()
	private var detailPopup: LiveProgramDetailPopup? = null
	private val liveTvRowAdapters = mutableListOf<ItemRowAdapter>()
	private val libraryLiveTvGuide = object : LiveTvGuide {
		override fun displayChannels(start: Int, max: Int) = Unit
		override fun getCurrentLocalStartDate(): LocalDateTime = LocalDateTime.now()
		override fun showProgramOptions() = Unit
		override fun setSelectedProgram(programView: RelativeLayout) = Unit
		override fun refreshFavorite(channelId: UUID) = refreshLiveTvChannelRows()
	}

	override fun onResume() {
		super.onResume()
		Handler(Looper.getMainLooper()).postDelayed({ refreshLiveTvRows() }, LIVE_TV_ROWS_REFRESH_DELAY_MS)
	}

	override fun onPause() {
		detailPopup?.dismiss()
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

	private fun refreshLiveTvChannelRows() = refreshLiveTvRows(includePrograms = false)

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

		getLiveTvRecordingsAndTimers(
			callback = { recordings, timers ->
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

					mRows.add(BrowseRowDef(
						getString(R.string.lbl_recent_recordings),
						BrowsingUtils.createLiveTVRecordingsRequest(),
						50,
					))
					rowLoader.loadRows(mRows)

					addStaticRow(getString(R.string.past_week), weekItems)
					addStaticRow(getString(R.string.scheduled_in_next_24_hours), nearTimers)
					addStaticRow(getString(R.string.past_24_hours), dayItems)
				} else {
					rowLoader.loadRows(mRows)
					addStaticRow(getString(R.string.scheduled_in_next_24_hours), nearTimers)
				}
			},
			errorCallback = { exception ->
				Timber.e(exception, "Failed to get Live TV recordings / timers")
				rowLoader.loadRows(mRows)
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

	override fun addAdditionalRows(rowAdapter: MutableObjectAdapter<Row>) {
		val gridRowAdapter = ArrayObjectAdapter(GridButtonPresenter(defaultGridButtonWidth, defaultGridButtonHeight)).apply {
			add(GridButton(
				LiveTvOption.LIVE_TV_CHANNELS_OPTION_ID,
				getString(R.string.channels),
				R.drawable.tile_land_tv,
			))
			add(GridButton(
				LiveTvOption.LIVE_TV_GUIDE_OPTION_ID,
				getString(R.string.lbl_live_tv_guide),
				R.drawable.tile_land_guide,
			))
			add(GridButton(
				LiveTvOption.LIVE_TV_RECORDINGS_OPTION_ID,
				getString(R.string.lbl_recorded_tv),
				R.drawable.tile_land_record,
			))

			if (Utils.canManageRecordings(userRepository.currentUser.value)) {
				add(GridButton(
					LiveTvOption.LIVE_TV_SCHEDULE_OPTION_ID,
					getString(R.string.lbl_schedule),
					R.drawable.tile_land_time,
				))
				add(GridButton(
					LiveTvOption.LIVE_TV_SERIES_OPTION_ID,
					getString(R.string.lbl_series),
					R.drawable.tile_land_series_timer,
				))
			}
		}

		rowAdapter.add(0, ListRow(HeaderItem(0, getString(R.string.pref_live_tv_cat)), gridRowAdapter))
	}

	override fun getChannelCardPresenter() = ChannelCardPresenter(onLongClick = ::handleChannelCardLongClick)

	private fun handleChannelCardLongClick(item: Any?, cardView: ChannelCardView): Boolean {
		val rowItem = item as? BaseRowItem ?: return false
		val program = getProgramForOptions(rowItem, cardView) ?: return false
		val channel = getChannelForOptions(rowItem, cardView)

		showProgramOptions(program, channel, cardView)
		return true
	}

	private fun getProgramForOptions(rowItem: BaseRowItem, cardView: ChannelCardView): BaseItemDto? {
		val item = cardView.currentItem ?: rowItem.baseItem ?: return null

		return when {
			rowItem.baseRowType == BaseRowType.LiveTvProgram &&
				rowItem.selectAction == BaseRowItemSelectAction.Play -> cardView.currentProgram ?: item

			rowItem.baseRowType == BaseRowType.LiveTvChannel ->
				cardView.currentProgram?.withChannelFallback(item) ?: item.withChannelFallback(item)

			else -> null
		}
	}

	private fun getChannelForOptions(rowItem: BaseRowItem, cardView: ChannelCardView): BaseItemDto? {
		if (rowItem.baseRowType != BaseRowType.LiveTvChannel) return null

		return cardView.currentItem ?: rowItem.baseItem
	}

	private fun showProgramOptions(program: BaseItemDto, favoriteChannel: BaseItemDto?, anchor: ChannelCardView) {
		if (favoriteChannel != null || program.channelId == null) {
			showProgramOptionsWithChannel(program, favoriteChannel, anchor)
			return
		}

		val channelId = program.channelId ?: return
		viewLifecycleOwner.lifecycleScope.launch {
			val loadedChannel = try {
				withContext(Dispatchers.IO) {
					api.liveTvApi.getChannel(channelId).content
				}
			} catch (error: ApiClientException) {
				Timber.w(error, "Unable to load Live TV channel $channelId for program actions")
				null
			}

			if (!isAdded || !anchor.isCurrentProgramActionTarget(program)) return@launch
			showProgramOptionsWithChannel(program, loadedChannel, anchor)
		}
	}

	private fun ChannelCardView.isCurrentProgramActionTarget(program: BaseItemDto) =
		isAttachedToWindow && hasFocus() && currentProgram?.id == program.id

	private fun showProgramOptionsWithChannel(program: BaseItemDto, favoriteChannel: BaseItemDto?, anchor: ChannelCardView) {
		val popupWidth = getProgramPopupWidth()
		val (popupX, popupY) = getProgramPopupPosition(anchor, popupWidth)
		val popupProgram = favoriteChannel?.let { program.withChannelFallback(it) } ?: program

		detailPopup?.dismiss()
		detailPopup = LiveProgramDetailPopup(
			requireActivity(),
			this,
			libraryLiveTvGuide,
			popupWidth,
			object : EmptyResponse(lifecycle) {
				override fun onResponse() {
					if (!isActive) return
					popupProgram.channelId?.let { channelId ->
						playbackHelper.retrieveAndPlay(channelId, false, requireContext())
					}
				}
			},
		).also { popup ->
			popup.setContent(popupProgram, anchor, favoriteChannel)
			popup.show(anchor, popupX, popupY)
		}
	}

	private fun BaseItemDto.withChannelFallback(channel: BaseItemDto) = copy(
		channelId = channelId ?: channel.id,
		channelName = channelName ?: channel.name,
		channelNumber = channelNumber ?: channel.number,
	)

	private fun getProgramPopupWidth(): Int {
		val horizontalMargin = Utils.convertDpToPixel(requireContext(), PROGRAM_DETAIL_POPUP_HORIZONTAL_MARGIN_DP)
		return minOf(
			Utils.convertDpToPixel(requireContext(), PROGRAM_DETAIL_POPUP_WIDTH_DP),
			(resources.displayMetrics.widthPixels - horizontalMargin * 2).coerceAtLeast(horizontalMargin),
		)
	}

	private fun getProgramPopupPosition(anchor: View, popupWidth: Int): Pair<Int, Int> {
		val popupHeight = Utils.convertDpToPixel(requireContext(), PROGRAM_DETAIL_POPUP_HEIGHT_DP)
		val location = IntArray(2)
		anchor.getLocationOnScreen(location)

		val screenWidth = resources.displayMetrics.widthPixels
		val screenHeight = resources.displayMetrics.heightPixels
		val x = ((screenWidth - popupWidth) / 2).coerceAtLeast(0)
		val maxY = (screenHeight - popupHeight).coerceAtLeast(0)
		val y = (location[1] - popupHeight / 2).coerceIn(0, maxY)
		return x to y
	}

	override fun getDefaultCardImageType() = ImageType.THUMB

	override fun getDefaultCardHeight() = LIVE_TV_TILE_HEIGHT_DP

	override fun getDefaultGridButtonWidth() = LIVE_TV_TILE_WIDTH_DP

	override fun getDefaultGridButtonHeight() = LIVE_TV_TILE_HEIGHT_DP

	private companion object {
		const val LIVE_TV_TILE_WIDTH_DP = 184
		const val LIVE_TV_TILE_HEIGHT_DP = 112
		const val SMART_ROW_INSERT_INDEX = 2
		const val SMART_ROW_ORDER = 0.5
		const val LIVE_TV_ROWS_REFRESH_DELAY_MS = 3_500L
		const val PROGRAM_DETAIL_POPUP_WIDTH_DP = 600
		const val PROGRAM_DETAIL_POPUP_HEIGHT_DP = 400
		const val PROGRAM_DETAIL_POPUP_HORIZONTAL_MARGIN_DP = 48
	}
}
