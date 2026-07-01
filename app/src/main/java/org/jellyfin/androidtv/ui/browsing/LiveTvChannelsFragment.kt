package org.jellyfin.androidtv.ui.browsing

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.androidtv.constant.PosterSize
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.databinding.HorizontalGridBrowseBinding
import org.jellyfin.androidtv.preference.LibraryPreferences
import org.jellyfin.androidtv.preference.PreferencesRepository
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.livetv.LiveTvCardActionHandler
import org.jellyfin.androidtv.ui.presentation.ChannelCardPresenter
import org.jellyfin.androidtv.ui.presentation.HorizontalGridPresenter
import org.jellyfin.androidtv.util.InfoLayoutHelper
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.isPageForward
import org.jellyfin.androidtv.util.isPageKey
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.android.ext.android.inject
import kotlin.math.max
import kotlin.math.roundToInt

class LiveTvChannelsFragment : Fragment(), View.OnKeyListener {
	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val itemLauncher by inject<ItemLauncher>()
	private val keyProcessor by inject<KeyProcessor>()
	private val playbackHelper by inject<PlaybackHelper>()
	private val preferencesRepository by inject<PreferencesRepository>()

	private val handler = Handler(Looper.getMainLooper())
	private val liveTvActions by lazy {
		LiveTvCardActionHandler(this, api, playbackHelper) { _, _ -> refreshChannels() }
	}

	private var binding: HorizontalGridBrowseBinding? = null
	private var folder: BaseItemDto? = null
	private var libraryPreferences: LibraryPreferences? = null
	private var adapter: ItemRowAdapter? = null
	private var gridPresenter: HorizontalGridPresenter? = null
	private var gridViewHolder: HorizontalGridPresenter.ViewHolder? = null
	private var gridView: BaseGridView? = null
	private var currentItem: BaseRowItem? = null
	private var selectedPosition = -1
	private var pendingSelectedPosition: Int? = null
	private var pageJumpSize = MIN_CHANNEL_PAGE_CHUNK_SIZE
	private var holdJumpSize = MIN_CHANNEL_HOLD_JUMP_SIZE
	private var lastHeldScrollAt = 0L
	private var dpadHoldConsumed = false
	private var playbackReturnPosition = -1
	private var justLoaded = true

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val liveTvFolder = Json.decodeFromString<BaseItemDto>(requireArguments().getString(Extras.Folder).orEmpty())
		folder = liveTvFolder
		lifecycleScope.launch {
			libraryPreferences = preferencesRepository.getLibraryPreferencesAsync(requireNotNull(liveTvFolder.displayPreferencesId))
			binding?.rowsFragment?.post { createGrid() }
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		val viewBinding = HorizontalGridBrowseBinding.inflate(inflater, container, false)
		binding = viewBinding
		return viewBinding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		binding?.apply {
			title.text = folder?.name ?: getString(R.string.channels)
			statusText.text = ""
			toolBar.visibility = View.GONE
			settings.visibility = View.GONE
			if (libraryPreferences != null) rowsFragment.post { createGrid() }
		}
	}

	override fun onResume() {
		super.onResume()

		if (justLoaded) {
			justLoaded = false
			return
		}

		handler.postDelayed({
			if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@postDelayed
			restorePlaybackSelection()
		}, CHANNEL_REFRESH_DELAY_MS)
	}

	override fun onPause() {
		liveTvActions.dismiss()
		super.onPause()
	}

	override fun onDestroyView() {
		handler.removeCallbacksAndMessages(null)
		gridView?.adapter = null
		gridView = null
		gridViewHolder = null
		gridPresenter = null
		adapter = null
		binding = null
		super.onDestroyView()
	}

	override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
		if (event == null) return false
		if (handleChannelNavigationKey(event)) return true

		if (event.action != KeyEvent.ACTION_UP) return false
		return keyProcessor.handleKey(keyCode, currentItem, requireActivity())
	}

	private fun createGrid() {
		val viewBinding = binding ?: return
		if (gridView != null) return
		val metrics = calculateGridMetrics()
		val presenter = HorizontalGridPresenter().apply {
			setNumberOfRows(metrics.rows)
			setShadowEnabled(false)
			enableChildRoundedCorners(false)
			setOnItemViewClickedListener(onItemClicked)
			setOnItemViewSelectedListener(onItemSelected)
		}

		val viewHolder = presenter.onCreateViewHolder(viewBinding.rowsFragment)
		val horizontalGridView = viewHolder.gridView
		pageJumpSize = metrics.pageJumpSize
		holdJumpSize = metrics.holdJumpSize
		viewBinding.rowsFragment.clipChildren = false
		viewBinding.rowsFragment.clipToPadding = false
		horizontalGridView.setGravity(Gravity.CENTER_VERTICAL)
		horizontalGridView.clipChildren = false
		horizontalGridView.clipToPadding = false
		horizontalGridView.setPadding(
			metrics.horizontalPaddingPx,
			metrics.verticalPaddingPx,
			metrics.horizontalPaddingPx,
			metrics.verticalPaddingPx,
		)
		horizontalGridView.setRowHeight(metrics.cardHeightPx)
		horizontalGridView.setHorizontalSpacing(metrics.horizontalSpacingPx)
		horizontalGridView.setVerticalSpacing(metrics.verticalSpacingPx)
		horizontalGridView.isFocusable = true
		horizontalGridView.setInitialPrefetchItemCount(metrics.pageJumpSize)
		horizontalGridView.setSmoothScrollMaxPendingMoves(CHANNEL_MAX_PENDING_DPAD_MOVES)
		horizontalGridView.setSmoothScrollSpeedFactor(CHANNEL_SCROLL_SPEED_FACTOR)
		horizontalGridView.setOnKeyInterceptListener { event -> handleChannelNavigationKey(event) }

		viewBinding.rowsFragment.removeAllViews()
		viewBinding.rowsFragment.addView(viewHolder.view)

		gridPresenter = presenter
		gridViewHolder = viewHolder
		gridView = horizontalGridView

		buildAdapter(metrics)
	}

	private fun buildAdapter(metrics: GridMetrics) {
		val viewBinding = binding ?: return
		val presenter = ChannelCardPresenter(
			metrics.cardWidthPx,
			metrics.cardHeightPx,
			liveTvActions::onLongClick,
		)

		val rowAdapter = ItemRowAdapter(
			requireContext(),
			BrowsingUtils.createLiveTVChannelsRequest(),
			metrics.chunkSize,
			presenter,
			null,
		).apply {
			setReRetrieveTriggers(arrayOf(ChangeTriggerType.TvPlayback, ChangeTriggerType.FavoriteUpdate))
			setRetrieveFinishedListener(Runnable {
				if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@Runnable
				onChannelsLoaded()
			})
		}

		adapter = rowAdapter
		gridPresenter?.onBindViewHolder(requireNotNull(gridViewHolder), rowAdapter)
		rowAdapter.Retrieve()
		viewBinding.counter.text = "0 | 0"
	}

	private fun onChannelsLoaded() {
		val viewBinding = binding ?: return
		val rowAdapter = adapter ?: return
		val total = rowAdapter.totalItems
		val position = if (total > 0) selectedPosition.coerceAtLeast(0) + 1 else 0
		viewBinding.counter.text = "$position | $total"

		val grid = gridView ?: return
		if (rowAdapter.itemsLoaded == 0) {
			grid.isFocusable = false
			viewBinding.title.text = folder?.name ?: getString(R.string.channels)
			return
		}

		grid.isFocusable = true
		keepGridFocused()
		pendingSelectedPosition?.let { pendingPosition ->
			val position = pendingPosition.coerceAtMost(rowAdapter.itemsLoaded - 1)
			if (position >= 0) {
				selectedPosition = position
				grid.selectedPosition = position
			}
			if (position == pendingPosition || rowAdapter.itemsLoaded >= rowAdapter.totalItems) {
				pendingSelectedPosition = null
			}
		}
		if (!grid.hasFocus()) grid.requestFocus()
	}

	private val onItemClicked = OnItemViewClickedListener { _, item, _, _ ->
		val rowItem = item as? BaseRowItem ?: return@OnItemViewClickedListener
		val rowAdapter = requireNotNull(adapter)
		playbackReturnPosition = rowAdapter.indexOf(rowItem).takeIf { it >= 0 } ?: selectedPosition
		itemLauncher.launch(rowItem, rowAdapter, requireContext())
	}

	private val onItemSelected = OnItemViewSelectedListener { _, item, _, _ ->
		handler.removeCallbacksAndMessages(DELAYED_ITEM_TOKEN)
		val rowItem = item as? BaseRowItem
		currentItem = rowItem

		val rowAdapter = adapter
		if (rowAdapter != null) {
			selectedPosition = if (rowItem == null) {
				-1
			} else {
				gridView?.selectedPosition?.takeIf { it >= 0 } ?: rowAdapter.indexOf(rowItem)
			}
			if (selectedPosition >= 0) rowAdapter.loadMoreItemsIfNeeded(selectedPosition)
			if (selectedPosition == pendingSelectedPosition) pendingSelectedPosition = null
		}

		updateCounter()
		val viewBinding = binding ?: return@OnItemViewSelectedListener
		if (rowItem == null) {
			viewBinding.title.text = folder?.name ?: getString(R.string.channels)
			viewBinding.infoRow.removeAllViews()
			backgroundService.clearBackgrounds()
			return@OnItemViewSelectedListener
		}

		viewBinding.title.text = rowItem.getName(requireContext())
		viewBinding.infoRow.removeAllViews()
		handler.postDelayed({
			if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@postDelayed
			val selectedItem = currentItem ?: return@postDelayed
			backgroundService.setBackground(selectedItem.baseItem)
			binding?.infoRow?.let { infoRow ->
				infoRow.removeAllViews()
				InfoLayoutHelper.addInfoRow(requireContext(), selectedItem.baseItem, infoRow, true)
			}
		}, DELAYED_ITEM_TOKEN, VIEW_SELECT_UPDATE_DELAY_MS)
	}

	private fun updateCounter() {
		val viewBinding = binding ?: return
		val rowAdapter = adapter ?: return
		val position = if (selectedPosition >= 0) selectedPosition + 1 else 0
		viewBinding.counter.text = "$position | ${rowAdapter.totalItems}"
	}

	private fun refreshChannels() {
		adapter?.Retrieve()
	}

	private fun restorePlaybackSelection() {
		val rowAdapter = adapter ?: return
		val restorePosition = playbackReturnPosition.takeIf { it >= 0 } ?: selectedPosition.takeIf { it >= 0 }
		if (restorePosition != null) {
			selectedPosition = restorePosition
			pendingSelectedPosition = restorePosition
		}

		val refreshed = rowAdapter.ReRetrieveIfNeeded()
		if (!refreshed && restorePosition != null) {
			pendingSelectedPosition = null
			if (rowAdapter.itemsLoaded > 0) {
				gridView?.selectedPosition = restorePosition.coerceAtMost(rowAdapter.itemsLoaded - 1)
				keepGridFocused()
			}
		}
		playbackReturnPosition = -1
	}

	private fun handleChannelNavigationKey(event: KeyEvent): Boolean {
		val keyCode = event.keyCode
		if (isChannelPageKey(keyCode)) {
			return when (event.action) {
				KeyEvent.ACTION_DOWN -> true
				KeyEvent.ACTION_UP -> moveChannels(isChannelPageForward(keyCode), pageJumpSize)
				else -> false
			}
		}

		if (!isHorizontalScrollKey(keyCode)) return false

		return when (event.action) {
			KeyEvent.ACTION_DOWN -> {
				dpadHoldConsumed = true
				if (event.repeatCount == 0 || event.eventTime - lastHeldScrollAt >= HELD_DPAD_SCROLL_INTERVAL_MS) {
					lastHeldScrollAt = event.eventTime
					moveChannels(isHorizontalScrollForward(keyCode), holdJumpSize)
				} else {
					keepGridFocused()
				}
				true
			}
			KeyEvent.ACTION_UP -> {
				lastHeldScrollAt = 0L
				dpadHoldConsumed.also {
					dpadHoldConsumed = false
					if (it) keepGridFocused()
				}
			}
			else -> false
		}
	}

	private fun moveChannels(forward: Boolean, distance: Int): Boolean {
		val rowAdapter = adapter ?: return true
		val grid = gridView ?: return true
		val total = rowAdapter.totalItems.takeIf { it > 0 } ?: rowAdapter.itemsLoaded
		if (total <= 0) return true

		val currentPosition = selectedPosition
			.takeIf { it >= 0 }
			?: grid.selectedPosition.takeIf { it >= 0 }
			?: 0
		val nextPosition = (currentPosition + if (forward) distance else -distance)
			.coerceIn(0, total - 1)

		selectedPosition = nextPosition
		updateCounter()

		if (nextPosition < rowAdapter.itemsLoaded) {
			pendingSelectedPosition = null
			grid.selectedPosition = nextPosition
			rowAdapter.loadMoreItemsIfNeeded(nextPosition)
		} else {
			pendingSelectedPosition = nextPosition
			grid.selectedPosition = (rowAdapter.itemsLoaded - 1).coerceAtLeast(0)
			rowAdapter.loadMoreItemsIfNeeded(rowAdapter.itemsLoaded - 1)
		}
		keepGridFocused()

		return true
	}

	private fun keepGridFocused() {
		val grid = gridView ?: return
		if ((adapter?.itemsLoaded ?: 0) <= 0) return
		grid.post {
			if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@post
			val focused = activity?.currentFocus
			if (isFocusInsideGrid(focused, grid)) return@post
			grid.requestFocus()
		}
	}

	private fun isFocusInsideGrid(focused: View?, grid: View): Boolean {
		var view = focused
		while (view != null) {
			if (view == grid) return true
			view = view.parent as? View
		}
		return false
	}

	private fun isChannelPageKey(keyCode: Int) = isPageKey(keyCode) ||
		keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
		keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN ||
		keyCode == KeyEvent.KEYCODE_PAGE_UP ||
		keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
		keyCode == KeyEvent.KEYCODE_BUTTON_L1 ||
		keyCode == KeyEvent.KEYCODE_BUTTON_L2 ||
		keyCode == KeyEvent.KEYCODE_BUTTON_R1 ||
		keyCode == KeyEvent.KEYCODE_BUTTON_R2

	private fun isChannelPageForward(keyCode: Int) = isPageForward(keyCode) ||
		keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
		keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
		keyCode == KeyEvent.KEYCODE_BUTTON_R1 ||
		keyCode == KeyEvent.KEYCODE_BUTTON_R2

	private fun isHorizontalScrollKey(keyCode: Int) =
		keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

	private fun isHorizontalScrollForward(keyCode: Int): Boolean {
		val isRtl = gridView?.layoutDirection == View.LAYOUT_DIRECTION_RTL
		return if (isRtl) keyCode == KeyEvent.KEYCODE_DPAD_LEFT else keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
	}

	private fun calculateGridMetrics(): GridMetrics {
		val viewBinding = requireNotNull(binding)
		val rows = rowsForPosterSize(libraryPreferences?.get(LibraryPreferences.posterSize) ?: PosterSize.MED)
		val gridWidth = viewBinding.rowsFragment.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
		val fallbackHeight = resources.displayMetrics.heightPixels - dp(FALLBACK_VERTICAL_CHROME_DP)
		val rawGridHeight = viewBinding.rowsFragment.height.takeIf { it > 0 } ?: fallbackHeight
		val gridHeight = (rawGridHeight - dp(BOTTOM_COUNTER_CLEARANCE_DP)).coerceAtLeast(rows * dp(MIN_CARD_HEIGHT_DP))
		val verticalSpacing = dp(ROW_SPACING_DP)

		val focusScale = resources.getFraction(R.fraction.card_scale_focus, 1, 1).toDouble()
		val cardScaling = max(focusScale - 1.0, 0.0)

		val availableCardHeight = (gridHeight - verticalSpacing * (rows - 1)).coerceAtLeast(rows * dp(MIN_CARD_HEIGHT_DP))
		val cardHeight = (availableCardHeight / (rows + cardScaling)).roundToInt().coerceAtLeast(dp(MIN_CARD_HEIGHT_DP))
		val cardWidth = (cardHeight * LIVE_TV_CARD_ASPECT_RATIO).roundToInt()
		val focusPadding = ((cardHeight * cardScaling) / 2.0).roundToInt().coerceAtLeast(0)
		val verticalPadding = ((gridHeight - (cardHeight * rows + verticalSpacing * (rows - 1))) / 2)
			.coerceAtLeast(focusPadding)
		val focusHorizontalPadding = ((cardWidth * cardScaling) / 2.0)
			.roundToInt()
			.coerceAtLeast(0)
		val horizontalPadding = focusHorizontalPadding.coerceAtLeast(dp(GRID_HORIZONTAL_PADDING_DP))
		val horizontalSpacing = (focusHorizontalPadding * CARD_SPACING_PCT)
			.roundToInt()
			.coerceAtLeast(dp(MIN_COLUMN_SPACING_DP))
		val cardsPerColumn = ((gridWidth.toDouble() / (cardWidth + horizontalSpacing)) + 0.5).roundToInt().coerceAtLeast(1)
		val estimatedVisibleCards = rows * cardsPerColumn

		return GridMetrics(
			rows = rows,
			cardWidthPx = cardWidth,
			cardHeightPx = cardHeight,
			horizontalPaddingPx = horizontalPadding,
			verticalPaddingPx = verticalPadding,
			horizontalSpacingPx = horizontalSpacing,
			verticalSpacingPx = verticalSpacing,
			pageJumpSize = estimatedVisibleCards.coerceAtLeast(rows),
			holdJumpSize = rows.coerceAtLeast(MIN_CHANNEL_HOLD_JUMP_SIZE),
			chunkSize = (estimatedVisibleCards * 2).coerceIn(MIN_CHANNEL_PAGE_CHUNK_SIZE, MAX_CHANNEL_PAGE_CHUNK_SIZE),
		)
	}

	private fun rowsForPosterSize(posterSize: PosterSize) = when (posterSize) {
		PosterSize.SMALLEST -> 7
		PosterSize.SMALL -> 6
		PosterSize.MED -> 5
		PosterSize.LARGE -> 4
		PosterSize.X_LARGE -> 2
	}

	private fun dp(value: Int) = org.jellyfin.androidtv.util.Utils.convertDpToPixel(requireContext(), value)

	private data class GridMetrics(
		val rows: Int,
		val cardWidthPx: Int,
		val cardHeightPx: Int,
		val horizontalPaddingPx: Int,
		val verticalPaddingPx: Int,
		val horizontalSpacingPx: Int,
		val verticalSpacingPx: Int,
		val pageJumpSize: Int,
		val holdJumpSize: Int,
		val chunkSize: Int,
	)

	private companion object {
		const val CHANNEL_REFRESH_DELAY_MS = 500L
		const val VIEW_SELECT_UPDATE_DELAY_MS = 250L
		const val FALLBACK_VERTICAL_CHROME_DP = 131
		const val BOTTOM_COUNTER_CLEARANCE_DP = 50
		const val MIN_CARD_HEIGHT_DP = 72
		const val ROW_SPACING_DP = 6
		const val GRID_HORIZONTAL_PADDING_DP = 44
		const val MIN_COLUMN_SPACING_DP = 8
		const val CARD_SPACING_PCT = 0.5
		const val LIVE_TV_CARD_ASPECT_RATIO = 16.0 / 9.0
		const val MIN_CHANNEL_PAGE_CHUNK_SIZE = 18
		const val MIN_CHANNEL_HOLD_JUMP_SIZE = 3
		const val MAX_CHANNEL_PAGE_CHUNK_SIZE = 120
		const val HELD_DPAD_SCROLL_INTERVAL_MS = 90L
		const val CHANNEL_MAX_PENDING_DPAD_MOVES = 2
		const val CHANNEL_SCROLL_SPEED_FACTOR = 0.65f
		val DELAYED_ITEM_TOKEN = Any()
	}
}
