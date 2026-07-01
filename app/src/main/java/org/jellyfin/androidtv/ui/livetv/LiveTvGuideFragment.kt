package org.jellyfin.androidtv.ui.livetv

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.databinding.LiveTvGuideBinding
import org.jellyfin.androidtv.ui.AsyncImageView
import org.jellyfin.androidtv.ui.FriendlyDateButton
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.androidtv.ui.HorizontalScrollViewListener
import org.jellyfin.androidtv.ui.LiveProgramDetailPopup
import org.jellyfin.androidtv.ui.ObservableHorizontalScrollView
import org.jellyfin.androidtv.ui.ObservableScrollView
import org.jellyfin.androidtv.ui.ProgramGridCell
import org.jellyfin.androidtv.ui.RecordingIndicatorView
import org.jellyfin.androidtv.ui.ScrollViewListener
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.InfoLayoutHelper
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.androidtv.util.apiclient.Response
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.androidtv.util.isPageForward
import org.jellyfin.androidtv.util.readCustomMessagesOnLifecycle
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaType
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class LiveTvGuideFragment : Fragment(), LiveTvGuide, View.OnKeyListener {
	private lateinit var mDisplayDate: TextView
	private lateinit var mTitle: TextView
	private lateinit var mChannelStatus: TextView
	private lateinit var mFilterStatus: TextView
	private lateinit var mSummary: TextView
	private lateinit var mImage: AsyncImageView
	private lateinit var mInfoRow: LinearLayout
	private lateinit var mChannels: LinearLayout
	private lateinit var mTimeline: LinearLayout
	private lateinit var mProgramRows: LinearLayout
	private lateinit var mChannelScroller: ObservableScrollView
	private lateinit var mTimelineScroller: HorizontalScrollView
	private lateinit var mSpinner: View
	private lateinit var mResetButton: View

	internal var mSelectedProgram: BaseItemDto? = null
	internal var mSelectedProgramView: RelativeLayout? = null

	private var mAllChannels: List<BaseItemDto> = emptyList()
	private var mFirstFocusChannelId: UUID? = null
	private var focusAtEnd = false
	private val mFilters = GuideFilters()

	private var mCurrentGuideStart: LocalDateTime = LocalDateTime.now()
	private var mCurrentGuideEnd: LocalDateTime = mCurrentGuideStart
	private var mCurrentDisplayChannelStartNdx = 0
	private var mCurrentDisplayChannelEndNdx = 0

	private var guideRowHeightPx = 0
	private var guideRowWidthPerMinPx = 0
	private var guideVisibleRows = 0

	private val mHandler = Handler()
	private val customMessageRepository by inject<CustomMessageRepository>()
	private val playbackHelper by inject<PlaybackHelper>()
	private val imageHelper by inject<ImageHelper>()
	private val playbackLauncher by inject<PlaybackLauncher>()
	private lateinit var showOptions: MutableStateFlow<Boolean>
	private lateinit var showFilterOptions: MutableStateFlow<Boolean>
	private var dateDialog: AlertDialog? = null
	private var mDetailPopup: LiveProgramDetailPopup? = null
	private var mDisplayProgramsTask: DisplayProgramsTask? = null
	private var guideLoadRequestId = 0
	private var programLoadRequestId = 0
	private var programLoadInFlight = false
	private var pendingGuideScrollY: Int? = null
	private var handledCenterLongPress = false
	private var popupTuneChannelId: UUID? = null
	private var ignoreNextFocusAutoLoad = false
	private val noOpRecordingIndicator = object : RecordingIndicatorView {
		override fun setRecTimer(id: String?) = Unit
		override fun setRecSeriesTimer(id: String?) = Unit
	}
	private var currentCellId = 0

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		guideRowHeightPx = Utils.convertDpToPixel(requireContext(), STANDALONE_GUIDE_ROW_HEIGHT_DP)
		guideRowWidthPerMinPx = Utils.convertDpToPixel(requireContext(), GUIDE_ROW_WIDTH_PER_MIN_DP)

		val binding = LiveTvGuideBinding.inflate(layoutInflater, container, false)

		mDisplayDate = binding.displayDate
		mTitle = binding.title
		mSummary = binding.summary
		mChannelStatus = binding.channelsStatus
		mFilterStatus = binding.filterStatus
		mChannelStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.guide_text_secondary))
		mFilterStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.guide_text_secondary))
		mInfoRow = binding.infoRow
		mImage = binding.programImage
		mChannels = binding.channels
		mTimeline = binding.timeline
		mProgramRows = binding.programRows
		mSpinner = binding.spinner
		mSpinner.visibility = View.VISIBLE

		showFilterOptions = addSettingsFilters(binding)
		binding.filterButton.setOnClickListener {
			showFilterOptions.value = true
		}
		binding.filterButton.contentDescription = getString(R.string.lbl_filters)

		showOptions = addSettingsOptions(binding)
		binding.optionsButton.setOnClickListener {
			showOptions.value = true
		}
		binding.optionsButton.contentDescription = getString(R.string.lbl_other_options)

		binding.dateButton.setOnClickListener {
			showDatePicker()
		}
		binding.dateButton.contentDescription = getString(R.string.lbl_select_date)

		mResetButton = binding.resetButton
		mResetButton.setOnClickListener {
			pageGuideTo(LocalDateTime.now())
		}

		mProgramRows.isFocusable = false
		mChannelScroller = binding.channelScroller
		val programVScroller = binding.programVScroller
		programVScroller.setScrollViewListener(ScrollViewListener { _, x, y, _, _ ->
			mChannelScroller.scrollTo(x, y)
			maybeAutoLoadScrolledChannels(y)
		})
		mChannelScroller.setScrollViewListener(ScrollViewListener { _, x, y, _, _ ->
			programVScroller.scrollTo(x, y)
		})

		mTimelineScroller = binding.timelineHScroller
		mTimelineScroller.isFocusable = false
		mTimelineScroller.isFocusableInTouchMode = false
		mTimeline.isFocusable = false
		mTimeline.isFocusableInTouchMode = false
		mChannelScroller.isFocusable = false
		mChannelScroller.isFocusableInTouchMode = false
		val programHScroller: ObservableHorizontalScrollView = binding.programHScroller
		programHScroller.scrollViewListener = HorizontalScrollViewListener { _, x, y, _, _ ->
			mTimelineScroller.scrollTo(x, y)
		}
		programHScroller.isFocusable = false
		programHScroller.isFocusableInTouchMode = false

		mChannels.isFocusable = false
		mChannelScroller.isFocusable = false

		readCustomMessagesOnLifecycle(lifecycle, customMessageRepository) { message ->
			if (message == CustomMessage.ActionComplete) dismissProgramOptions()
		}

		return binding.root
	}

	private fun getGuideHours() = if (mFilters.any()) FILTERED_HOURS else NORMAL_HOURS

	private fun load() {
		mCurrentGuideStart = LocalDateTime.now()
		fillTimeLine(mCurrentGuideStart, getGuideHours())
		val requestId = ++guideLoadRequestId
		TvManager.loadAllChannels(this) loadChannels@ { _ ->
			if (requestId != guideLoadRequestId) return@loadChannels null

			val pageSize = guidePageSize()

			mAllChannels = TvManager.getAllChannels().orEmpty()
			if (mAllChannels.isNotEmpty()) {
				displayChannelList()
				displayChannels(0, pageSize)
			} else {
				mSpinner.visibility = View.GONE
			}
			null
		}
	}

	override fun refreshFavorite(channelId: UUID, isFavorite: Boolean) {
		for (i in 0 until mChannels.childCount) {
			val header = mChannels.getChildAt(i) as? GuideChannelHeader ?: continue
			if (header.channel.id == channelId) {
				TvManager.getChannelByID(channelId)?.let { channel ->
					header.channel = channel
				}
				header.setFavorite(isFavorite)
			}
		}
	}

	override fun onResume() {
		super.onResume()

		mFilters.load()
		doLoad()
	}

	internal fun doLoad() {
		if (
			TvManager.shouldForceReload() ||
			mCurrentGuideStart.plusMinutes(30).isBefore(LocalDateTime.now()) ||
			mChannels.childCount == 0 ||
			mProgramRows.childCount == 0
		) {
			mFirstFocusChannelId = null
			load()
		}
	}

	internal fun reloadGuide() {
		mFilters.load()
		TvManager.forceReload()
		doLoad()
	}

	override fun onPause() {
		super.onPause()

		mDisplayProgramsTask?.cancel(true)
		guideLoadRequestId++
		programLoadRequestId++
		programLoadInFlight = false
		pendingGuideScrollY = null
		mHandler.removeCallbacks(idleGuidePrefetchTask)
		mDetailPopup?.dismiss()
	}

	override fun onDestroy() {
		super.onDestroy()

		if (mCurrentGuideStart.isAfter(LocalDateTime.now())) {
			TvManager.forceReload()
		}
	}

	override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean = when {
		event == null -> false
		event.action == KeyEvent.ACTION_UP -> onKeyUp(keyCode, event)
		event.action == KeyEvent.ACTION_DOWN && event.isLongPress -> onKeyLongPress(keyCode)
		event.action == KeyEvent.ACTION_DOWN -> onKeyDown(keyCode, event)
		else -> false
	}

	private fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
		KeyEvent.KEYCODE_ENTER,
		KeyEvent.KEYCODE_DPAD_CENTER -> {
			event.startTracking()
			true
		}
		KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
		KeyEvent.KEYCODE_MEDIA_REWIND,
		KeyEvent.KEYCODE_MEDIA_NEXT,
		KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true
		else -> false
	}

	private fun onKeyLongPress(keyCode: Int): Boolean = when (keyCode) {
		KeyEvent.KEYCODE_ENTER,
		KeyEvent.KEYCODE_DPAD_CENTER -> {
			handledCenterLongPress = true
			when (mSelectedProgramView) {
				is ProgramGridCell -> showProgramOptions()
				is GuideChannelHeader -> showChannelOptions()
			}
			true
		}
		else -> false
	}

	private fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
		when (keyCode) {
			KeyEvent.KEYCODE_MENU -> showFilterOptions.value = true
			KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
			KeyEvent.KEYCODE_MEDIA_REWIND,
			KeyEvent.KEYCODE_MEDIA_NEXT,
			KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return handleChannelPageKey(keyCode)
			KeyEvent.KEYCODE_ENTER,
			KeyEvent.KEYCODE_DPAD_CENTER -> {
				if (handledCenterLongPress) {
					handledCenterLongPress = false
					return true
				}
				if (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS == 0) {
					val selectedView = mSelectedProgramView
					val selectedProgram = mSelectedProgram
					if (selectedView is ProgramGridCell && selectedProgram != null) {
						if (selectedProgram.isNoProgramDataPlaceholder()) return true
						val channelId = selectedProgram.channelId
						if (selectedProgram.startDate?.isBefore(LocalDateTime.now()) == true && channelId != null) {
							playbackHelper.retrieveAndPlay(channelId, false, requireContext())
						} else {
							showProgramOptions()
						}
						return true
					} else if (selectedView is GuideChannelHeader) {
						playbackHelper.getItemsToPlay(
							requireContext(),
							selectedView.channel,
							false,
							false,
							object : Response<List<BaseItemDto>>(lifecycle) {
								override fun onResponse(response: List<BaseItemDto>) {
									if (!isActive) return
									playbackLauncher.launch(requireContext(), response)
								}
							},
						)
					}
				}
				return false
			}
			KeyEvent.KEYCODE_MEDIA_PLAY,
			KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
				val selectedProgram = mSelectedProgram
				val channelId = selectedProgram?.channelId
				if (
					mDetailPopup?.isShowing() != true &&
					channelId != null
				) {
					playbackHelper.retrieveAndPlay(channelId, false, requireContext())
					return true
				}
			}
			KeyEvent.KEYCODE_DPAD_RIGHT -> {
				val selectedView = mSelectedProgramView
				if (
					requireActivity().currentFocus is ProgramGridCell &&
					selectedView is ProgramGridCell &&
					selectedView.isLast()
				) {
					requestGuidePage(mCurrentGuideEnd)
				}
			}
			KeyEvent.KEYCODE_DPAD_LEFT -> {
				val selectedView = mSelectedProgramView
				val selectedProgram = mSelectedProgram
				if (
					requireActivity().currentFocus is ProgramGridCell &&
					selectedView is ProgramGridCell &&
					selectedView.isFirst() &&
					selectedProgram?.startDate?.isAfter(LocalDateTime.now()) == true
				) {
					focusAtEnd = true
					requestGuidePage(mCurrentGuideStart.minusHours(getGuideHours().toLong()))
				}
			}
		}

		return false
	}

	private fun handleChannelPageKey(keyCode: Int): Boolean {
		if (mDetailPopup?.isShowing() == true) return true
		if (programLoadInFlight || mAllChannels.isEmpty()) return true

		updateGuideVisibleRows()
		pageGuideChannels(
			requireActivity(),
			mProgramRows,
			mChannels,
			guideVisibleRows.takeIf { it > 0 } ?: DEFAULT_VISIBLE_ROWS,
			isPageForward(keyCode),
		)
		return true
	}

	private val datePickedListener = View.OnClickListener { view ->
		pageGuideTo((view as FriendlyDateButton).date)
		dateDialog?.dismiss()
	}

	private fun showDatePicker() {
		val scrollPane = layoutInflater.inflate(R.layout.horizontal_scroll_pane, null) as FrameLayout
		val scrollItems = scrollPane.findViewById<LinearLayout>(R.id.scrollItems)
		for (increment in 0L until 15L) {
			scrollItems.addView(FriendlyDateButton(requireContext(), LocalDateTime.now().plusDays(increment), datePickedListener))
		}

		dateDialog = AlertDialog.Builder(requireContext())
			.setTitle(R.string.lbl_select_date)
			.setView(scrollPane)
			.setNegativeButton(R.string.btn_cancel) { _, _ -> }
			.show()
	}

	private fun requestGuidePage(startTime: LocalDateTime) {
		AlertDialog.Builder(requireContext())
			.setTitle(R.string.lbl_load_guide_data)
			.setMessage(
				if (startTime.isAfter(mCurrentGuideStart)) {
					getString(R.string.msg_live_tv_next, getGuideHours())
				} else {
					getString(R.string.msg_live_tv_prev, getGuideHours())
				}
			)
			.setPositiveButton(R.string.lbl_yes) { _, _ ->
				pageGuideTo(startTime)
			}
			.setNegativeButton(R.string.lbl_no, null)
			.show()
	}

	private fun pageGuideTo(startTime: LocalDateTime) {
		var targetStartTime = startTime
		if (targetStartTime.isBefore(LocalDateTime.now())) targetStartTime = LocalDateTime.now()
		Timber.i("Paging Live TV guide to %s", targetStartTime)
		mSelectedProgram?.let { selectedProgram ->
			mFirstFocusChannelId = selectedProgram.channelId
		}
		fillTimeLine(targetStartTime, getGuideHours())
		loadProgramData()
	}

	fun dismissProgramOptions() {
		mDetailPopup?.dismiss()
	}

	override fun showProgramOptions() {
		val selectedProgram = mSelectedProgram ?: return
		val selectedView = mSelectedProgramView as? ProgramGridCell ?: return
		if (selectedProgram.isNoProgramDataPlaceholder()) return
		showProgramOptions(selectedProgram, selectedView, null, mImage)
	}

	private fun showChannelOptions() {
		val selectedHeader = mSelectedProgramView as? GuideChannelHeader ?: return
		val selectedProgramCell = findProgramCellForChannelHeader(selectedHeader)
		val selectedProgram = selectedProgramCell
			?.getProgram()
			?.takeUnless { program -> program.isNoProgramDataPlaceholder() }

		showProgramOptions(
			program = selectedProgram ?: selectedHeader.channel,
			selectedView = selectedProgramCell ?: noOpRecordingIndicator,
			favoriteChannel = selectedHeader.channel,
			anchor = selectedHeader,
		)
	}

	private fun showProgramOptions(
		program: BaseItemDto,
		selectedView: RecordingIndicatorView,
		favoriteChannel: BaseItemDto?,
		anchor: View,
	) {
		if (mDetailPopup == null) {
			mDetailPopup = LiveProgramDetailPopup(
				requireActivity(),
				this,
				this,
				mSummary.width + 20,
				object : EmptyResponse(lifecycle) {
					override fun onResponse() {
						if (!isActive) return
						(popupTuneChannelId ?: mSelectedProgram?.channelId)?.let { channelId ->
							playbackHelper.retrieveAndPlay(channelId, false, requireContext())
						}
					}
				},
			)
		}

		popupTuneChannelId = favoriteChannel?.id ?: program.channelId
		mDetailPopup?.setContent(program, selectedView, favoriteChannel)
		mDetailPopup?.show(anchor, mTitle.left, mTitle.top - 10)
	}

	private fun findProgramCellForChannelHeader(header: GuideChannelHeader): ProgramGridCell? {
		val programRow = findProgramRowForChannel(header.channel.id) ?: return null
		val selectedProgramId = mSelectedProgram?.id
		val now = LocalDateTime.now()

		return (0 until programRow.childCount)
			.mapNotNull { index -> programRow.getChildAt(index) as? ProgramGridCell }
			.firstOrNull { cell -> selectedProgramId != null && cell.getProgram().id == selectedProgramId }
			?: (0 until programRow.childCount)
				.mapNotNull { index -> programRow.getChildAt(index) as? ProgramGridCell }
				.firstOrNull { cell ->
					val program = cell.getProgram()
					program.startDate?.isBefore(now) == true && program.endDate?.isAfter(now) == true
				}
	}

	private fun findProgramRowForChannel(channelId: UUID): LinearLayout? =
		(0 until mProgramRows.childCount)
			.mapNotNull { index -> mProgramRows.getChildAt(index) as? LinearLayout }
			.firstOrNull { row ->
				(0 until row.childCount)
					.mapNotNull { index -> row.getChildAt(index) as? ProgramGridCell }
					.firstOrNull()
					?.getProgram()
					?.channelId == channelId
			}

	override fun displayChannels(start: Int, max: Int) {
		displayChannels(start, max, getCurrentFocusChannelId().takeIf { max > guidePageSize() })
	}

	private fun displayChannels(
		start: Int,
		max: Int,
		focusChannelId: UUID?,
		showSpinner: Boolean = true,
	) {
		var end = start + max
		if (end > mAllChannels.size) {
			end = mAllChannels.size
		}

		if (mFilters.any()) {
			mCurrentDisplayChannelStartNdx = 0
			mCurrentDisplayChannelEndNdx = mAllChannels.size - 1
		} else {
			mCurrentDisplayChannelStartNdx = start
			mCurrentDisplayChannelEndNdx = end - 1
		}

		if (focusChannelId != null) {
			mFirstFocusChannelId = focusChannelId
		}

		Timber.v("Display channels pre-execute")

		loadProgramData(showSpinner)
	}

	private fun loadProgramData(showSpinner: Boolean = true) {
		if (showSpinner) {
			mSpinner.visibility = View.VISIBLE
			mProgramRows.removeAllViews()
			if (mFilters.any()) mChannels.removeAllViews()
			mChannelStatus.text = ""
			mFilterStatus.text = ""
		}
		mHandler.removeCallbacks(idleGuidePrefetchTask)
		programLoadInFlight = true
		val requestId = ++programLoadRequestId
		TvManager.getProgramsAsync(
			this,
			mCurrentDisplayChannelStartNdx,
			mCurrentDisplayChannelEndNdx,
			mCurrentGuideStart,
			mCurrentGuideEnd,
			object : EmptyResponse(lifecycle) {
				override fun onResponse() {
					if (!isActive || requestId != programLoadRequestId) return
					Timber.v("Programs response")
					mDisplayProgramsTask?.cancel(true)
					mDisplayProgramsTask = DisplayProgramsTask(showSpinner)
					mDisplayProgramsTask?.execute(mCurrentDisplayChannelStartNdx, mCurrentDisplayChannelEndNdx)
				}
			},
		)
	}

	private data class GuideRow(
		val channel: BaseItemDto,
		val row: LinearLayout,
		val index: Int,
	)

	private inner class DisplayProgramsTask(
		private val clearBeforeBuild: Boolean,
	) : AsyncTask<Int, Int, List<GuideRow>>() {
		private var firstFocusView: View? = null
		private var focusChannelFound = false

		override fun onPreExecute() {
			Timber.v("Display programs pre-execute")
			if (clearBeforeBuild) {
				mProgramRows.removeAllViews()
				if (mFilters.any()) mChannels.removeAllViews()
			}
		}

		override fun doInBackground(vararg params: Int?): List<GuideRow> {
			val start = params.getOrNull(0) ?: return emptyList()
			val end = params.getOrNull(1) ?: return emptyList()
			val rows = mutableListOf<GuideRow>()
			var first = true
			var prevRow: LinearLayout? = null

			Timber.v("About to iterate programs")
			for (i in start..end) {
				if (isCancelled) return emptyList()
				val channel = TvManager.getChannel(i)
				val programs = TvManager.getProgramsForChannel(channel.id, mFilters)
				val row = getProgramRow(programs, channel.id) ?: continue

				if (first) {
					first = false
					firstFocusView = row.preferredFocusChild()
				}

				prevRow?.let { previous ->
					TvManager.setFocusParams(row, previous, true)
					TvManager.setFocusParams(previous, row, false)
				}
				prevRow = row

				if (channel.id == mFirstFocusChannelId) {
					firstFocusView = row.preferredFocusChild(focusAtEnd)
					focusChannelFound = true
				}
				rows.add(GuideRow(channel, row, i))
			}
			return rows
		}

		override fun onPostExecute(result: List<GuideRow>) {
			Timber.v("Display programs post execute")
			val currentFocus = requireActivity().currentFocus
			val guideHadFocus = isGuideFocus(currentFocus)
			val keepChannelFocus = !mFilters.any() && currentFocus is GuideChannelHeader
			val restoreScrollY = if (clearBeforeBuild) null else pendingGuideScrollY ?: mChannelScroller.scrollY
			val restoreFocusIndex = restoreScrollY?.let { (it / guideRowHeightPx).coerceIn(0, mAllChannels.lastIndex) }
			val restoreFocusView = restoreFocusIndex?.let { index ->
				result.firstOrNull { it.index == index }?.row?.preferredFocusChild()
			}
			pendingGuideScrollY = null
			mProgramRows.removeAllViews()
			if (mFilters.any()) mChannels.removeAllViews()
			updateGuideScrollPadding()

			result.forEach { guideRow ->
				if (mFilters.any()) {
					val header = getChannelHeader(guideRow.channel)
					mChannels.addView(header)
					header.loadImage()
				}
				mProgramRows.addView(guideRow.row)
			}

			if (focusChannelFound) {
				focusAtEnd = false
			}
			mFirstFocusChannelId = null

			mChannelStatus.text = "${result.size} of ${mAllChannels.size} channels"
			mFilterStatus.text = "${mFilters} for ${getGuideHours()} hours"
			mFilterStatus.setTextColor(
				ContextCompat.getColor(
					requireContext(),
					if (mFilters.any()) R.color.white else R.color.guide_text_secondary,
				)
			)

			mResetButton.visibility = if (mCurrentGuideStart.isAfter(LocalDateTime.now())) View.VISIBLE else View.GONE

			programLoadInFlight = false
			mSpinner.visibility = View.GONE
			val focusView = when {
				clearBeforeBuild || focusChannelFound -> firstFocusView
				guideHadFocus && !keepChannelFocus -> restoreFocusView
				else -> null
			}
			if (focusView != null) {
				ignoreNextFocusAutoLoad = true
				if (focusView.requestFocus()) {
					focusView.post { ignoreNextFocusAutoLoad = false }
				} else {
					ignoreNextFocusAutoLoad = false
					if (clearBeforeBuild) scrollToDisplayWindowStart()
				}
			} else if (clearBeforeBuild) {
				scrollToDisplayWindowStart()
			}
			if (!clearBeforeBuild) {
				mChannelScroller.post {
					mChannelScroller.scrollTo(0, restoreScrollY ?: mChannelScroller.scrollY)
					maybeAutoLoadScrolledChannels(mChannelScroller.scrollY)
				}
			}
		}
	}

	private fun LinearLayout.preferredFocusChild(atEnd: Boolean = false): View {
		if (childCount == 0) return this
		return getChildAt(if (atEnd) childCount - 1 else 0)
	}

	private fun getChannelHeader(channel: BaseItemDto) =
		GuideChannelHeader(requireContext(), this, channel, STANDALONE_GUIDE_ROW_HEIGHT_DP, true)

	private fun displayChannelList() {
		if (mFilters.any()) return
		if (
			mChannels.childCount == mAllChannels.size &&
			mAllChannels.indices.all { index -> (mChannels.getChildAt(index) as? GuideChannelHeader)?.channel?.id == mAllChannels[index].id }
		) {
			return
		}

		mChannels.removeAllViews()
		mChannels.setVerticalPadding(0, 0)
		mAllChannels.forEach { channel ->
			val header = getChannelHeader(channel)
			mChannels.addView(header)
			header.loadImage()
		}
	}

	private fun maybeAutoLoadAdjacentChannels(programView: RelativeLayout) {
		if (mFilters.any() || programLoadInFlight || mAllChannels.isEmpty()) return

		val channelIndex = getDisplayedChannelIndex(programView) ?: return
		val focusChannelId = getFocusChannelId(programView).takeUnless { programView is GuideChannelHeader }
		if (
			channelIndex <= mCurrentDisplayChannelStartNdx + CHANNEL_BUFFER_ROWS ||
			channelIndex >= mCurrentDisplayChannelEndNdx - CHANNEL_BUFFER_ROWS
		) {
			loadVisibleChannelWindow(focusChannelId)
		}
	}

	private fun maybeAutoLoadScrolledChannels(scrollY: Int) {
		if (mFilters.any() || mAllChannels.isEmpty()) return
		if (programLoadInFlight) {
			pendingGuideScrollY = scrollY
			return
		}

		val focusChannelId = getCurrentFocusChannelId().takeUnless { requireActivity().currentFocus is GuideChannelHeader }
		loadVisibleChannelWindow(focusChannelId, scrollY)
	}

	private fun loadVisibleChannelWindow(
		focusChannelId: UUID?,
		scrollY: Int = mChannelScroller.scrollY,
	) {
		updateGuideVisibleRows()
		val visibleRows = guideVisibleRows.takeIf { it > 0 } ?: DEFAULT_VISIBLE_ROWS
		val firstVisibleChannel = (scrollY / guideRowHeightPx).coerceIn(0, mAllChannels.lastIndex)
		val lastVisibleChannel = minOf(mAllChannels.lastIndex, firstVisibleChannel + visibleRows - 1)

		if (
			firstVisibleChannel >= mCurrentDisplayChannelStartNdx + CHANNEL_BUFFER_ROWS &&
			lastVisibleChannel <= mCurrentDisplayChannelEndNdx - CHANNEL_BUFFER_ROWS
		) {
			return
		}

		loadChannelWindow(firstVisibleChannel, focusChannelId)
	}

	private fun loadChannelWindow(firstVisibleChannel: Int, focusChannelId: UUID?) {
		if (programLoadInFlight) return

		val pageSize = guidePageSize()
		val maxStart = maxOf(0, mAllChannels.size - pageSize)
		val newStart = (firstVisibleChannel - CHANNEL_BUFFER_ROWS).coerceIn(0, maxStart)
		val newEnd = minOf(mAllChannels.size - 1, newStart + pageSize - 1)

		if (newStart == mCurrentDisplayChannelStartNdx && newEnd == mCurrentDisplayChannelEndNdx) return

		displayChannels(
			newStart,
			newEnd - newStart + 1,
			focusChannelId,
			showSpinner = false,
		)
	}

	private fun getDisplayedChannelIndex(programView: RelativeLayout): Int? {
		val channelId = when (programView) {
			is GuideChannelHeader -> programView.channel.id
			is ProgramGridCell -> programView.getProgram().channelId
			else -> null
		} ?: return null

		return mAllChannels.indexOfFirst { channel -> channel.id == channelId }.takeIf { it >= 0 }
	}

	private fun guidePageSize(): Int {
		updateGuideVisibleRows()
		return (guideVisibleRows.takeIf { it > 0 } ?: DEFAULT_VISIBLE_ROWS) + (CHANNEL_BUFFER_ROWS * 2)
	}

	private fun updateGuideVisibleRows() {
		if (guideRowHeightPx == 0) return
		val visibleRows = mChannelScroller.height / guideRowHeightPx
		if (visibleRows > 0) guideVisibleRows = visibleRows
	}

	private fun updateGuideScrollPadding() {
		val topRows = if (mFilters.any()) 0 else mCurrentDisplayChannelStartNdx
		val bottomRows = if (mFilters.any() || mAllChannels.isEmpty()) {
			0
		} else {
			mAllChannels.lastIndex - mCurrentDisplayChannelEndNdx
		}
		mChannels.setVerticalPadding(0, 0)
		mProgramRows.setVerticalPadding(topRows, bottomRows)
	}

	private fun scrollToDisplayWindowStart() {
		if (mCurrentDisplayChannelStartNdx > 0) {
			mChannelScroller.post { mChannelScroller.scrollTo(0, mCurrentDisplayChannelStartNdx * guideRowHeightPx) }
		}
	}

	private fun isGuideFocus(view: View?): Boolean {
		var current = view
		while (current != null) {
			if (current == mChannels || current == mProgramRows) return true
			current = current.parent as? View
		}
		return false
	}

	private fun View.setVerticalPadding(topRows: Int, bottomRows: Int) {
		setPadding(
			paddingLeft,
			topRows * guideRowHeightPx,
			paddingRight,
			bottomRows * guideRowHeightPx,
		)
	}

	private val idleGuidePrefetchTask = Runnable {
		if (mFilters.any() || programLoadInFlight || mAllChannels.isEmpty()) return@Runnable
		loadVisibleChannelWindow(getCurrentFocusChannelId())
	}

	private fun scheduleIdleGuidePrefetch() {
		mHandler.removeCallbacks(idleGuidePrefetchTask)
		mHandler.postDelayed(idleGuidePrefetchTask, IDLE_GUIDE_PREFETCH_DELAY_MS)
	}

	private fun getCurrentFocusChannelId(): UUID? =
		mSelectedProgramView?.let(::getFocusChannelId) ?: mSelectedProgram?.channelId

	private fun getFocusChannelId(programView: RelativeLayout): UUID? = when (programView) {
		is GuideChannelHeader -> programView.channel.id
		is ProgramGridCell -> programView.getProgram().channelId
		else -> mSelectedProgram?.channelId
	}

	private fun getProgramRow(programs: List<BaseItemDto>, channelId: UUID): LinearLayout? {
		val programRow = LinearLayout(requireContext())

		if (programs.isEmpty()) {
			if (mFilters.any()) return null

			val minutes = ((mCurrentGuideEnd.toInstant(ZoneOffset.UTC).toEpochMilli() - mCurrentGuideStart.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000).toInt()
			var slot = 0
			do {
				val empty = createNoProgramDataBaseItem(
					requireContext(),
					channelId,
					mCurrentGuideStart.plusMinutes(30L * slot),
					mCurrentGuideEnd.plusMinutes(30L * (slot + 1)),
				)

				val cell = ProgramGridCell(requireContext(), this, empty, false, true)
				cell.id = currentCellId++
				cell.layoutParams = ViewGroup.LayoutParams(30 * guideRowWidthPerMinPx, guideRowHeightPx)
				if (slot == 0) cell.setFirst()
				if (slot == (minutes / 30) - 1) cell.setLast()
				programRow.addView(cell)
				slot++
			} while ((30 * slot) < minutes)
			return programRow
		}

		var prevEnd = getCurrentLocalStartDate()
		for (item in programs) {
			var start = item.startDate ?: getCurrentLocalStartDate()
			if (start.isBefore(getCurrentLocalStartDate())) {
				start = getCurrentLocalStartDate()
			}

			if (start.isBefore(prevEnd)) continue

			if (start.isAfter(prevEnd)) {
				val empty = createNoProgramDataBaseItem(
					requireContext(),
					channelId,
					prevEnd,
					start,
				)

				val cell = ProgramGridCell(requireContext(), this, empty, false, true)
				cell.id = currentCellId++
				cell.layoutParams = ViewGroup.LayoutParams(
					((start.toInstant(ZoneOffset.UTC).toEpochMilli() - prevEnd.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000).toInt() * guideRowWidthPerMinPx,
					guideRowHeightPx,
				)
				if (prevEnd == mCurrentGuideStart) {
					cell.setFirst()
				}
				programRow.addView(cell)
			}

			var end = item.endDate ?: getCurrentLocalEndDate()
			if (end.isAfter(getCurrentLocalEndDate())) end = getCurrentLocalEndDate()
			prevEnd = end
			val duration = (end.toInstant(ZoneOffset.UTC).toEpochMilli() - start.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000
			if (duration > 0) {
				val programCell = ProgramGridCell(requireContext(), this, item, false, true)
				programCell.id = currentCellId++
				programCell.layoutParams = ViewGroup.LayoutParams(duration.toInt() * guideRowWidthPerMinPx, guideRowHeightPx)
				if (start == mCurrentGuideStart) {
					programCell.setFirst()
				}
				if (end == mCurrentGuideEnd) {
					programCell.setLast()
				}

				programRow.addView(programCell)
			}
		}

		if (prevEnd.isBefore(mCurrentGuideEnd)) {
			val empty = createNoProgramDataBaseItem(
				requireContext(),
				channelId,
				prevEnd,
				mCurrentGuideEnd,
			)

			val cell = ProgramGridCell(requireContext(), this, empty, false, true)
			cell.id = currentCellId++
			cell.layoutParams = ViewGroup.LayoutParams(
				((mCurrentGuideEnd.toInstant(ZoneOffset.UTC).toEpochMilli() - prevEnd.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000).toInt() * guideRowWidthPerMinPx,
				guideRowHeightPx,
			)
			programRow.addView(cell)
		}

		return programRow
	}

	private fun fillTimeLine(start: LocalDateTime, hours: Int) {
		mCurrentGuideStart = start
			.withMinute(start.minute)
			.withSecond(0)
			.withNano(0)

		mDisplayDate.text = TimeUtils.getFriendlyDate(requireContext(), mCurrentGuideStart)
		mCurrentGuideEnd = mCurrentGuideStart.plusHours(hours.toLong())
		val oneHour = 60 * guideRowWidthPerMinPx
		var interval = if (mCurrentGuideStart.minute >= 30) 60 - mCurrentGuideStart.minute else 30 - mCurrentGuideStart.minute
		mTimeline.removeAllViews()

		var current = mCurrentGuideStart
		while (current.isBefore(mCurrentGuideEnd)) {
			mTimeline.addView(TextView(requireContext()).apply {
				text = requireContext().getTimeFormatter().format(current)
				gravity = Gravity.CENTER_VERTICAL
				setPadding(
					Utils.convertDpToPixel(requireContext(), 10),
					0,
					Utils.convertDpToPixel(requireContext(), 10),
					0,
				)
				setTextColor(ContextCompat.getColor(requireContext(), R.color.guide_text_secondary))
				textSize = 13f
				typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
				width = if (interval != 60) {
					if (interval < 15) 15 * guideRowWidthPerMinPx else interval * guideRowWidthPerMinPx
				} else {
					oneHour
				}
			})
			current = current.plusMinutes(interval.toLong())
			interval = if (interval < 30) 30 else 60
		}
	}

	override fun getCurrentLocalStartDate(): LocalDateTime = mCurrentGuideStart

	fun getCurrentLocalEndDate(): LocalDateTime = mCurrentGuideEnd

	private val detailUpdateTask = Runnable {
		if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@Runnable
		refreshSelectedProgram()
	}

	internal fun detailUpdateInternal() {
		val selectedProgram = mSelectedProgram ?: return

		mInfoRow.removeAllViews()
		if (selectedProgram.isNoProgramDataPlaceholder()) {
			mTitle.text = getString(R.string.no_program_data)
			mSummary.text = ""
			mImage.visibility = View.INVISIBLE
			mDisplayDate.text = TimeUtils.getFriendlyDate(requireContext(), selectedProgram.startDate ?: mCurrentGuideStart)
			return
		}

		mTitle.text = selectedProgram.name
		mSummary.text = selectedProgram.overview

		InfoLayoutHelper.addInfoRow(requireContext(), selectedProgram, mInfoRow, false)

		mDisplayDate.text = TimeUtils.getFriendlyDate(requireContext(), selectedProgram.startDate)
		val url = imageHelper.getPrimaryImageUrl(selectedProgram, height = ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT)
		if (url == null) {
			mImage.visibility = View.INVISIBLE
		} else {
			mImage.visibility = View.VISIBLE
			mImage.load(url, null, ContextCompat.getDrawable(requireContext(), R.drawable.blank10x10), 0.0, 0)
		}

		val selectedView = mSelectedProgramView as? ProgramGridCell
		if (mDetailPopup?.isShowing() == true && selectedView != null) {
			mDetailPopup?.setContent(selectedProgram, selectedView)
		}
	}

	override fun setSelectedProgram(programView: RelativeLayout) {
		mSelectedProgramView = programView
		if (ignoreNextFocusAutoLoad) {
			ignoreNextFocusAutoLoad = false
		} else {
			programView.post { maybeAutoLoadAdjacentChannels(programView) }
			scheduleIdleGuidePrefetch()
		}

		when (programView) {
			is ProgramGridCell -> {
				mSelectedProgram = programView.getProgram()
				mHandler.removeCallbacks(detailUpdateTask)
				mHandler.postDelayed(detailUpdateTask, 500)
			}
			is GuideChannelHeader -> {
				val programRow = findProgramRowForChannel(programView.channel.id) ?: return
				for (i in 0 until programRow.childCount) {
					val programCell = programRow.getChildAt(i) as ProgramGridCell
					val program = programCell.getProgram()
					if (
						program.startDate?.isBefore(LocalDateTime.now()) == true &&
						program.endDate?.isAfter(LocalDateTime.now()) == true
					) {
						mSelectedProgram = program
						mHandler.removeCallbacks(detailUpdateTask)
						mHandler.postDelayed(detailUpdateTask, 500)
						return
					}
				}
			}
		}
	}

	private fun BaseItemDto.isNoProgramDataPlaceholder() =
		mediaType == MediaType.UNKNOWN && name == getString(R.string.no_program_data)

	companion object {
		private const val STANDALONE_GUIDE_ROW_HEIGHT_DP = 64
		private const val CHANNEL_BUFFER_ROWS = 1
		private const val DEFAULT_VISIBLE_ROWS = 8
		private const val IDLE_GUIDE_PREFETCH_DELAY_MS = 700L
		const val GUIDE_ROW_HEIGHT_DP = 55
		const val GUIDE_ROW_WIDTH_PER_MIN_DP = 7
		const val PAGE_SIZE = DEFAULT_VISIBLE_ROWS + (CHANNEL_BUFFER_ROWS * 2)
		const val NORMAL_HOURS = 9
		const val FILTERED_HOURS = 4
	}
}
