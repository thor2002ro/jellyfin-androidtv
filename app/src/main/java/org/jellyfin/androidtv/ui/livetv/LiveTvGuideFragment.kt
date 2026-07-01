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
import org.jellyfin.androidtv.ui.GuidePagingButton
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
import org.jellyfin.androidtv.util.getLoadChannelsLabel
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
	private var handledCenterLongPress = false
	private var popupTuneChannelId: UUID? = null
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
		TvManager.loadAllChannels(this) { index ->
			val startIndex = if (index >= PAGE_SIZE) {
				index - (PAGE_SIZE / 2)
			} else {
				0
			}

			mAllChannels = TvManager.getAllChannels().orEmpty()
			if (mAllChannels.isNotEmpty()) {
				displayChannels(startIndex, PAGE_SIZE)
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
			mChannels.childCount == 0
		) {
			load()
			mFirstFocusChannelId = TvManager.getLastLiveTvChannel()
		}
	}

	override fun onPause() {
		super.onPause()

		mDisplayProgramsTask?.cancel(true)
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
		if (mSpinner.visibility == View.VISIBLE || mAllChannels.isEmpty()) return true

		if (guideVisibleRows == 0) {
			guideVisibleRows = maxOf(1, mChannelScroller.height / guideRowHeightPx)
		}
		pageGuideChannels(
			requireActivity(),
			mProgramRows,
			mChannels,
			guideVisibleRows,
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
		TvManager.forceReload()
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
		val channelRowIndex = (0 until mChannels.childCount)
			.firstOrNull { index -> mChannels.getChildAt(index) == header }
			?: return null
		val programRow = mProgramRows.getChildAt(channelRowIndex) as? LinearLayout ?: return null
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

	override fun displayChannels(start: Int, max: Int) {
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
		Timber.v("Display channels pre-execute")
		mSpinner.visibility = View.VISIBLE

		loadProgramData()
	}

	private fun loadProgramData() {
		mProgramRows.removeAllViews()
		mChannels.removeAllViews()
		mChannelStatus.text = ""
		mFilterStatus.text = ""
		TvManager.getProgramsAsync(
			this,
			mCurrentDisplayChannelStartNdx,
			mCurrentDisplayChannelEndNdx,
			mCurrentGuideStart,
			mCurrentGuideEnd,
			object : EmptyResponse(lifecycle) {
				override fun onResponse() {
					if (!isActive) return
					Timber.v("Programs response")
					mDisplayProgramsTask?.cancel(true)
					mDisplayProgramsTask = DisplayProgramsTask()
					mDisplayProgramsTask?.execute(mCurrentDisplayChannelStartNdx, mCurrentDisplayChannelEndNdx)
				}
			},
		)
	}

	private inner class DisplayProgramsTask : AsyncTask<Int, Int, Void?>() {
		private var firstFocusView: View? = null
		private var displayedChannels = 0

		override fun onPreExecute() {
			Timber.v("Display programs pre-execute")
			mChannels.removeAllViews()
			mProgramRows.removeAllViews()

			if (mCurrentDisplayChannelStartNdx > 0) {
				var pageUpStart = mCurrentDisplayChannelStartNdx - PAGE_SIZE
				if (pageUpStart < 0) {
					pageUpStart = 0
				}

				displayedChannels = 0

				val label = getLoadChannelsLabel(
					requireContext(),
					mAllChannels[pageUpStart].number,
					mAllChannels[mCurrentDisplayChannelStartNdx - 1].number,
				)
				addPagingRow(pageUpStart, label)
			}
		}

		override fun doInBackground(vararg params: Int?): Void? {
			val start = params.getOrNull(0) ?: return null
			val end = params.getOrNull(1) ?: return null
			var first = true
			var prevRow: LinearLayout? = null

			Timber.v("About to iterate programs")
			for (i in start..end) {
				if (isCancelled) return null
				val channel = TvManager.getChannel(i)
				val programs = TvManager.getProgramsForChannel(channel.id, mFilters)
				val row = getProgramRow(programs, channel.id) ?: continue

				if (first) {
					first = false
					firstFocusView = row
				}

				prevRow?.let { previous ->
					TvManager.setFocusParams(row, previous, true)
					TvManager.setFocusParams(previous, row, false)
				}
				prevRow = row

				requireActivity().runOnUiThread {
					if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@runOnUiThread

					val header = getChannelHeader(channel)
					mChannels.addView(header)
					header.loadImage()
					mProgramRows.addView(row)
					if (channel.id == mFirstFocusChannelId) {
						firstFocusView = if (focusAtEnd) row.getChildAt(row.childCount - 1) else row
						focusAtEnd = false
						mFirstFocusChannelId = null
					}
				}

				displayedChannels++
			}
			return null
		}

		override fun onPostExecute(result: Void?) {
			Timber.v("Display programs post execute")
			if (mCurrentDisplayChannelEndNdx < mAllChannels.size - 1 && !mFilters.any()) {
				var pageDnEnd = mCurrentDisplayChannelEndNdx + PAGE_SIZE
				if (pageDnEnd >= mAllChannels.size) pageDnEnd = mAllChannels.size - 1

				val label = getLoadChannelsLabel(
					requireContext(),
					mAllChannels[mCurrentDisplayChannelEndNdx + 1].number,
					mAllChannels[pageDnEnd].number,
				)
				addPagingRow(mCurrentDisplayChannelEndNdx + 1, label)
			}

			mChannelStatus.text = "$displayedChannels of ${mAllChannels.size} channels"
			mFilterStatus.text = "${mFilters} for ${getGuideHours()} hours"
			mFilterStatus.setTextColor(
				ContextCompat.getColor(
					requireContext(),
					if (mFilters.any()) R.color.white else R.color.guide_text_secondary,
				)
			)

			mResetButton.visibility = if (mCurrentGuideStart.isAfter(LocalDateTime.now())) View.VISIBLE else View.GONE

			mSpinner.visibility = View.GONE
			firstFocusView?.requestFocus()
		}
	}

	private fun getChannelHeader(channel: BaseItemDto) =
		GuideChannelHeader(requireContext(), this, channel, STANDALONE_GUIDE_ROW_HEIGHT_DP, true)

	private fun addPagingRow(start: Int, label: String) {
		mChannels.addView(TextView(requireContext()), guideRowLayoutParams())
		mProgramRows.addView(
			GuidePagingButton(requireContext(), this, start, label, true),
			guideRowLayoutParams(),
		)
	}

	private fun guideRowLayoutParams() = LinearLayout.LayoutParams(
		LinearLayout.LayoutParams.MATCH_PARENT,
		guideRowHeightPx,
	)

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
		when (programView) {
			is ProgramGridCell -> {
				mSelectedProgram = programView.getProgram()
				mHandler.removeCallbacks(detailUpdateTask)
				mHandler.postDelayed(detailUpdateTask, 500)
			}
			is GuideChannelHeader -> {
				for (i in 0 until mChannels.childCount) {
					if (programView == mChannels.getChildAt(i)) {
						val programRow = mProgramRows.getChildAt(i) as? LinearLayout ?: return
						for (ii in 0 until programRow.childCount) {
							val programCell = programRow.getChildAt(ii) as ProgramGridCell
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
		}
	}

	private fun BaseItemDto.isNoProgramDataPlaceholder() =
		mediaType == MediaType.UNKNOWN && name == getString(R.string.no_program_data)

	companion object {
		private const val STANDALONE_GUIDE_ROW_HEIGHT_DP = 64
		const val GUIDE_ROW_HEIGHT_DP = 55
		const val GUIDE_ROW_WIDTH_PER_MIN_DP = 7
		const val PAGE_SIZE = 75
		const val NORMAL_HOURS = 9
		const val FILTERED_HOURS = 4
	}
}
