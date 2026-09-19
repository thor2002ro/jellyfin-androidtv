package org.jellyfin.androidtv.ui.presentation

import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyHorizontalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem

class HorizontalGridPresenter : Presenter() {
	class State {
		private var boundAdapter: Any? = null
		var position = -1
			private set

		fun bindAdapter(adapter: Any): Boolean {
			if (boundAdapter === adapter) return false
			boundAdapter = adapter
			return true
		}

		fun unbindAdapter() {
			boundAdapter = null
		}

		fun setPosition(position: Int) {
			if (position >= 0) this.position = position
		}
	}

	class ViewHolder(val gridView: ComposeView) : Presenter.ViewHolder(gridView)

	private data class AdapterSnapshot(
		val items: List<Any> = emptyList(),
		val revision: Int = 0,
	)

	private data class GridConfig(
		val imageType: ImageType = ImageType.POSTER,
		val cardHeight: Int = 100,
		val rows: Int = 1,
		val horizontalSpacing: Int = 0,
		val verticalSpacing: Int = 0,
		val paddingStart: Int = 0,
		val paddingEnd: Int = 0,
		val verticalPadding: Int = 0,
		val showInfo: Boolean = false,
		val uniformAspect: Boolean = true,
	)

	private val state = State()
	private val snapshot = MutableStateFlow(AdapterSnapshot())
	private val config = MutableStateFlow(GridConfig())
	private val focusRequestGeneration = MutableStateFlow(0)
	private var adapter: ObjectAdapter? = null
	private var boundViewHolder: ViewHolder? = null
	private val selectionNotifications = GridSelectionNotificationTracker()
	private val adapterSyncGate = GridAdapterSyncGate()
	private var revision = 0
	private var focusGeneration = 0
	private var selectedListener: OnItemViewSelectedListener? = null
	private var clickedListener: OnItemViewClickedListener? = null
	private var longClickedListener: ((Any?, View) -> Boolean)? = null
	private var directionalKeyListener: Runnable? = null
	private var keyListener: View.OnKeyListener? = null
	private val directionalKeyGuard = BrowseGridDirectionalKeyGuard()

	private val adapterObserver = object : ObjectAdapter.DataObserver() {
		override fun onChanged() = scheduleAdapterSync()
	}

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val composeView = ComposeView(parent.context).apply {
			setParentCompositionContext(parent.findViewTreeCompositionContext())
			setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
			setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
			isFocusable = true
			isFocusableInTouchMode = true
			layoutParams = ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
			)
		}
		val holder = ViewHolder(composeView)
		boundViewHolder = holder
		composeView.setContent {
			JellyfinTheme {
				HorizontalBrowseGrid(holder)
			}
		}
		return holder
	}

	override fun onBindViewHolder(holder: Presenter.ViewHolder, item: Any?) {
		val objectAdapter = item as ObjectAdapter
		if (state.bindAdapter(objectAdapter)) {
			adapterSyncGate.cancel()
			adapter?.unregisterObserver(adapterObserver)
			adapter = objectAdapter
			selectionNotifications.reset()
			objectAdapter.registerObserver(adapterObserver)
		}
		boundViewHolder = holder as ViewHolder
		syncAdapterNow()
	}

	override fun onUnbindViewHolder(holder: Presenter.ViewHolder) {
		if (boundViewHolder !== holder) return
		adapterSyncGate.cancel()
		adapter?.unregisterObserver(adapterObserver)
		adapter = null
		state.unbindAdapter()
		boundViewHolder = null
		snapshot.value = AdapterSnapshot(revision = ++revision)
	}

	fun configure(
		imageType: ImageType,
		cardHeight: Int,
		rows: Int,
		horizontalSpacing: Int,
		verticalSpacing: Int,
		paddingStart: Int,
		paddingEnd: Int,
		verticalPadding: Int,
		showInfo: Boolean = false,
		uniformAspect: Boolean = true,
	) {
		config.value = GridConfig(
			imageType = imageType,
			cardHeight = cardHeight,
			rows = rows.coerceAtLeast(1),
			horizontalSpacing = horizontalSpacing,
			verticalSpacing = verticalSpacing,
			paddingStart = paddingStart,
			paddingEnd = paddingEnd,
			verticalPadding = verticalPadding,
			showInfo = showInfo,
			uniformAspect = uniformAspect,
		)
	}

	fun setNumberOfRows(rows: Int) {
		require(rows >= 0) { "Invalid number of rows" }
		config.value = config.value.copy(rows = rows.coerceAtLeast(1))
	}

	fun getNumberOfRows(): Int = config.value.rows

	fun getPosition(): Int = state.position

	fun setPosition(position: Int) {
		if (position < 0) return
		state.setPosition(position)
		focusRequestGeneration.value = ++focusGeneration
	}

	fun setOnItemViewSelectedListener(listener: OnItemViewSelectedListener?) {
		selectedListener = listener
	}

	fun setOnItemViewClickedListener(listener: OnItemViewClickedListener?) {
		clickedListener = listener
	}

	fun setOnItemLongClickedListener(listener: ((Any?, View) -> Boolean)?) {
		longClickedListener = listener
	}

	fun setOnDirectionalKeyListener(listener: Runnable?) {
		directionalKeyListener = listener
	}

	fun setOnKeyListener(listener: View.OnKeyListener?) {
		keyListener = listener
	}

	private fun scheduleAdapterSync() {
		val gridView = boundViewHolder?.gridView ?: return
		adapterSyncGate.request(
			post = { action -> gridView.post { action() } },
			sync = ::syncAdapterNow,
		)
	}

	private fun syncAdapterNow() {
		val currentAdapter = adapter ?: return
		val wasEmpty = snapshot.value.items.isEmpty()
		val items = List(currentAdapter.size()) { index -> requireNotNull(currentAdapter[index]) }
		snapshot.value = AdapterSnapshot(items, ++revision)
		if (wasEmpty && items.isNotEmpty()) setPosition(state.position.coerceAtLeast(0))
	}

	private fun select(holder: ViewHolder, position: Int, item: Any) {
		state.setPosition(position)
		holder.gridView.tag = (item as? BaseRowItem)?.itemId
		if (!selectionNotifications.shouldNotify(position, item)) return
		selectedListener?.onItemSelected(holder, item, null, null)
	}

	private fun click(holder: ViewHolder, position: Int, item: Any) {
		state.setPosition(position)
		clickedListener?.onItemClicked(holder, item, null, null)
	}

	@OptIn(ExperimentalFoundationApi::class)
	@Composable
	private fun HorizontalBrowseGrid(holder: ViewHolder) {
		val snapshotValue by snapshot.collectAsState()
		val configValue by config.collectAsState()
		val requestGeneration by focusRequestGeneration.collectAsState()
		val entries = remember(snapshotValue.revision) {
			snapshotValue.items.mapIndexed { index, item ->
				HorizontalBrowseGridItem(
					adapterPosition = index,
					item = item,
					key = (item as? BaseRowItem)?.itemId?.toString() ?: "horizontal-browse-$index",
				)
			}
		}
		val initialPosition = remember(entries.size, state.position) {
			findHorizontalGridInitialItemIndex(entries.size, state.position)
		}
		val gridState = rememberLazyStaggeredGridState(initialFirstVisibleItemIndex = initialPosition)
		val restoreFocusRequester = remember { FocusRequester() }
		val focusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
		var focusedPosition by remember { mutableIntStateOf(state.position.coerceAtLeast(0)) }

		LaunchedEffect(requestGeneration, entries.size) {
			if (entries.isEmpty()) return@LaunchedEffect
			val generation = focusGeneration
			val position = findHorizontalGridInitialItemIndex(entries.size, state.position)
			focusedPosition = position
			val key = entries[position].key
			val existingRequester = focusRequesters[key]
			if (generation != focusGeneration) return@LaunchedEffect
			if (existingRequester != null && runCatching { existingRequester.requestFocus() }.getOrDefault(false)) {
				return@LaunchedEffect
			}
			gridState.scrollToItem(position)
			val requester = snapshotFlow { focusRequesters[key] }.first { it != null }
			if (generation != focusGeneration) return@LaunchedEffect
			runCatching { requireNotNull(requester).requestFocus() }
		}

		LazyHorizontalStaggeredGrid(
			rows = StaggeredGridCells.Fixed(configValue.rows),
			state = gridState,
			modifier = Modifier
				.fillMaxSize()
				.focusGroup()
				.focusRestorer(restoreFocusRequester),
			contentPadding = PaddingValues(
				start = configValue.paddingStart.dp,
				end = configValue.paddingEnd.dp,
				top = configValue.verticalPadding.dp,
				bottom = configValue.verticalPadding.dp,
			),
			verticalArrangement = Arrangement.spacedBy(configValue.verticalSpacing.dp),
			horizontalItemSpacing = configValue.horizontalSpacing.dp,
		) {
			items(
				items = entries,
				key = { it.key },
				contentType = { (it.item as? BaseRowItem)?.baseRowType },
			) { entry ->
				val rowItem = entry.item as? BaseRowItem ?: return@items
				val requester = remember(entry.key) { FocusRequester() }
				DisposableEffect(entry.key, requester) {
					focusRequesters[entry.key] = requester
					onDispose {
						if (focusRequesters[entry.key] === requester) focusRequesters.remove(entry.key)
					}
				}
				var focused by remember(entry.key) { mutableStateOf(false) }
				LaunchedEffect(focused, snapshotValue.revision, entry.item) {
					if (focused) select(holder, entry.adapterPosition, entry.item)
				}
				val scale by animateFloatAsState(
					targetValue = if (focused) 1.15f else 1f,
					animationSpec = spring(),
					label = "horizontal_browse_card_focus",
				)
				val focusModifier = if (entry.adapterPosition == focusedPosition) {
					Modifier.focusRequester(restoreFocusRequester)
				} else {
					Modifier
				}

				CardViewHolderContent(
					item = rowItem,
					focused = focused,
					showInfo = configValue.showInfo,
					imageType = configValue.imageType,
					staticHeight = configValue.cardHeight,
					uniformAspect = configValue.uniformAspect,
					modifier = focusModifier
						.focusRequester(requester)
						.onPreviewKeyEvent { event ->
							val nativeEvent = event.nativeKeyEvent
							if (keyListener?.onKey(holder.gridView, nativeEvent.keyCode, nativeEvent) == true) {
								return@onPreviewKeyEvent true
							}
							val directional = event.key == Key.DirectionUp ||
								event.key == Key.DirectionDown ||
								event.key == Key.DirectionLeft ||
								event.key == Key.DirectionRight
							if (
								directional && event.type == KeyEventType.KeyDown &&
								directionalKeyGuard.tryAccept(nativeEvent.eventTime)
							) {
								directionalKeyListener?.run()
							}
							false
						}
						.onFocusChanged { focusState ->
							focused = focusState.isFocused
							if (focusState.isFocused) {
								focusedPosition = entry.adapterPosition
								select(holder, entry.adapterPosition, entry.item)
							}
						}
						.semantics(mergeDescendants = true) {}
						.combinedClickable(
							onClick = { click(holder, entry.adapterPosition, entry.item) },
							onLongClick = {
								holder.gridView.tag = rowItem.itemId
								longClickedListener?.invoke(entry.item, holder.gridView)
							},
						)
						.zIndex(if (focused) 1f else 0f)
						.scale(scale),
				)
			}
		}
	}
}

internal data class HorizontalBrowseGridItem(
	val adapterPosition: Int,
	val item: Any,
	val key: String,
)

internal fun findHorizontalGridInitialItemIndex(itemCount: Int, selectedPosition: Int): Int {
	if (itemCount <= 0) return 0
	return selectedPosition.coerceAtLeast(0).coerceAtMost(itemCount - 1)
}
