package org.jellyfin.androidtv.ui.browsing

import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.databinding.HorizontalGridBrowseBinding
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.ChannelCardPresenter
import org.jellyfin.androidtv.ui.presentation.HorizontalGridPresenter
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.androidtv.util.Utils
import org.koin.android.ext.android.inject
import java.text.MessageFormat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class BrowseLiveTvChannelsFragment : Fragment(), View.OnKeyListener {
	private val handler = Handler()
	private val itemLauncher by inject<ItemLauncher>()
	private val keyProcessor by inject<KeyProcessor>()

	private var _binding: HorizontalGridBrowseBinding? = null
	private val binding get() = requireNotNull(_binding)
	private var adapter: ItemRowAdapter? = null
	private var gridPresenter: HorizontalGridPresenter? = null
	private var gridView: BaseGridView? = null
	private var gridViewHolder: Presenter.ViewHolder? = null
	private var currentItem: BaseRowItem? = null
	private var justLoaded = true

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		_binding = HorizontalGridBrowseBinding.inflate(inflater, container, false)
		return binding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		binding.title.setText(R.string.channels)
		binding.statusText.setText(R.string.all_channels)
		binding.toolBar.visibility = View.GONE

		binding.rowsFragment.post {
			if (_binding == null) return@post

			createGrid()
			buildAdapter()
			loadChannels()
		}
	}

	override fun onResume() {
		super.onResume()

		val currentAdapter = adapter
		if (!justLoaded && currentAdapter != null) {
			handler.postDelayed({
				if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@postDelayed

				if (!currentAdapter.ReRetrieveIfNeeded()) {
					loadChannels()
				}
			}, 500)
		} else {
			justLoaded = false
		}
	}

	override fun onDestroyView() {
		super.onDestroyView()

		_binding = null
		gridPresenter = null
		gridView = null
		gridViewHolder = null
	}

	override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
		if (event?.action != KeyEvent.ACTION_UP) return false
		return keyProcessor.handleKey(keyCode, currentItem, requireActivity())
	}

	private fun createGrid() {
		val layoutValues = calculateGridLayoutValues()
		val presenter = HorizontalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE).apply {
			setNumberOfRows(CHANNEL_ROWS)
			setShadowEnabled(false)
			onItemViewSelectedListener = selectedListener
			onItemViewClickedListener = clickedListener
		}
		gridPresenter = presenter

		val viewHolder = presenter.onCreateViewHolder(binding.rowsFragment)
		gridViewHolder = viewHolder
		gridView = (viewHolder as HorizontalGridPresenter.ViewHolder).gridView.apply {
			setGravity(Gravity.CENTER_VERTICAL)
			horizontalSpacing = layoutValues.horizontalSpacing
			verticalSpacing = layoutValues.verticalSpacing
			setPadding(
				layoutValues.horizontalPadding,
				layoutValues.verticalPadding,
				layoutValues.horizontalPadding,
				layoutValues.verticalPadding,
			)
			isFocusable = true
			setOnKeyListener(this@BrowseLiveTvChannelsFragment)
		}

		binding.rowsFragment.removeAllViews()
		binding.rowsFragment.addView(viewHolder.view)
	}

	private fun buildAdapter() {
		val layoutValues = calculateGridLayoutValues()
		val newAdapter = ItemRowAdapter(
			requireContext(),
			BrowsingUtils.createLiveTVChannelsRequest(),
			CHANNEL_CHUNK_SIZE,
			ChannelCardPresenter(layoutValues.tileWidth, layoutValues.tileHeight),
			null,
		)
		adapter = newAdapter

		newAdapter.setRetrieveFinishedListener {
			val currentBinding = _binding ?: return@setRetrieveFinishedListener
			val currentGridView = gridView ?: return@setRetrieveFinishedListener
			if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@setRetrieveFinishedListener

			updateCounter(currentGridView.selectedPosition + 1, newAdapter.totalItems)
			currentBinding.statusText.text = getString(
				R.string.lbl_tv_channel_status,
				newAdapter.itemsLoaded,
				newAdapter.totalItems,
			)
			currentGridView.isFocusable = newAdapter.itemsLoaded > 0
			if (newAdapter.itemsLoaded > 0) {
				currentGridView.requestFocus()
			}
		}

		val viewHolder = gridViewHolder ?: return
		gridPresenter?.onBindViewHolder(viewHolder, newAdapter)
	}

	private fun calculateGridLayoutValues(): GridLayoutValues {
		val measuredWidth = binding.rowsFragment.width
		val measuredHeight = binding.rowsFragment.height
		val containerWidth = if (measuredWidth > 0) measuredWidth else resources.displayMetrics.widthPixels
		val containerHeight = if (measuredHeight > 0) measuredHeight else resources.displayMetrics.heightPixels / 2

		val horizontalSpacing = clamp(
			(containerWidth * 0.0035f).roundToInt(),
			Utils.convertDpToPixel(requireContext(), MIN_GRID_SPACING_DP),
			Utils.convertDpToPixel(requireContext(), MAX_GRID_SPACING_DP),
		)
		val verticalSpacing = clamp(
			(containerHeight * 0.007f).roundToInt(),
			Utils.convertDpToPixel(requireContext(), MIN_GRID_SPACING_DP),
			Utils.convertDpToPixel(requireContext(), MAX_GRID_SPACING_DP),
		)
		val horizontalPadding = clamp(
			(containerWidth * 0.008f).roundToInt(),
			Utils.convertDpToPixel(requireContext(), MIN_GRID_PADDING_DP),
			Utils.convertDpToPixel(requireContext(), MAX_GRID_PADDING_DP),
		)
		val verticalPadding = clamp(
			(containerHeight * 0.008f).roundToInt(),
			Utils.convertDpToPixel(requireContext(), MIN_GRID_PADDING_DP),
			Utils.convertDpToPixel(requireContext(), MAX_GRID_PADDING_DP),
		)

		val availableWidth = containerWidth - (horizontalPadding * 2)
		val availableHeight = containerHeight - (verticalPadding * 2) - (verticalSpacing * (CHANNEL_ROWS - 1))
		val tileWidth = availableWidth / 6
		val tileHeight = availableHeight / CHANNEL_ROWS

		return GridLayoutValues(tileWidth, tileHeight, horizontalSpacing, verticalSpacing, horizontalPadding, verticalPadding)
	}

	private fun clamp(value: Int, min: Int, max: Int) = max(min, min(max, value))

	private data class GridLayoutValues(
		val tileWidth: Int,
		val tileHeight: Int,
		val horizontalSpacing: Int,
		val verticalSpacing: Int,
		val horizontalPadding: Int,
		val verticalPadding: Int,
	)

	private fun loadChannels() {
		adapter?.Retrieve()
	}

	private fun updateCounter(position: Int, total: Int) {
		val currentBinding = _binding ?: return

		val safePosition = if (total > 0) max(position, 1) else 0
		currentBinding.counter.text = MessageFormat.format("{0} | {1}", safePosition, total)
	}

	private val selectedListener = OnItemViewSelectedListener { _, item, _, _ ->
		val currentAdapter = adapter ?: return@OnItemViewSelectedListener
		val currentBinding = _binding ?: return@OnItemViewSelectedListener
		val rowItem = item as? BaseRowItem
		if (rowItem == null) {
			currentItem = null
			updateCounter(0, 0)
			return@OnItemViewSelectedListener
		}

		currentItem = rowItem
		val selectedPosition = gridView?.selectedPosition ?: currentAdapter.indexOf(rowItem)
		updateCounter(selectedPosition + 1, currentAdapter.totalItems)
		currentBinding.statusText.text = getString(
			R.string.lbl_tv_channel_status,
			currentAdapter.itemsLoaded,
			currentAdapter.totalItems,
		)
		currentAdapter.loadMoreItemsIfNeeded(selectedPosition)
	}

	private val clickedListener = OnItemViewClickedListener { _, item, _, _ ->
		val rowItem = item as? BaseRowItem ?: return@OnItemViewClickedListener
		val currentAdapter = adapter ?: return@OnItemViewClickedListener

		itemLauncher.launch(rowItem, currentAdapter, requireContext())
	}

	private companion object {
		const val CHANNEL_CHUNK_SIZE = 100
		const val CHANNEL_ROWS = 4
		const val MIN_GRID_SPACING_DP = 4
		const val MAX_GRID_SPACING_DP = 8
		const val MIN_GRID_PADDING_DP = 6
		const val MAX_GRID_PADDING_DP = 18
	}
}
