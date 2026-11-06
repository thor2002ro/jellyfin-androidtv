package org.jellyfin.androidtv.ui.presentation

import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
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
import kotlin.math.min

class ComposeVerticalGridPresenter : Presenter() {
	class ViewHolder(val gridView: ComposeView) : Presenter.ViewHolder(gridView)

	private data class AdapterSnapshot(
		val items: List<Any> = emptyList(),
		val revision: Int = 0,
	)

	private data class GridConfig(
		val imageType: ImageType = ImageType.POSTER,
		val cardHeight: Int = 100,
		val columns: Int = 1,
		val horizontalSpacing: Int = 0,
		val verticalSpacing: Int = 0,
		val paddingLeft: Int = 0,
	)

	private val snapshot = MutableStateFlow(AdapterSnapshot())
	private val config = MutableStateFlow(GridConfig())
	private val focusRequestGeneration = MutableStateFlow(0)
	private var adapter: ObjectAdapter? = null
	private var boundViewHolder: ViewHolder? = null
	private var selectedPosition = -1
	private val selectionNotifications = GridSelectionNotificationTracker()
	private val adapterSyncGate = GridAdapterSyncGate()
	private var revision = 0
	private var focusGeneration = 0
	private var focusMovePending = false
	private var selectedListener: OnItemViewSelectedListener? = null
	private var clickedListener: OnItemViewClickedListener? = null
	private var directionalKeyListener: Runnable? = null
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
			layoutParams = ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
			)
		}
		val holder = ViewHolder(composeView)
		boundViewHolder = holder
		composeView.setContent {
			JellyfinTheme {
				VerticalBrowseGrid(holder)
			}
		}
		return holder
	}

	override fun onBindViewHolder(holder: Presenter.ViewHolder, item: Any?) {
		val objectAdapter = item as ObjectAdapter
		if (adapter !== objectAdapter) {
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
		boundViewHolder = null
		snapshot.value = AdapterSnapshot(revision = ++revision)
	}

	fun configure(
		imageType: ImageType,
		cardHeight: Int,
		columns: Int,
		horizontalSpacing: Int,
		verticalSpacing: Int,
		paddingLeft: Int,
	) {
		config.value = GridConfig(
			imageType = imageType,
			cardHeight = cardHeight,
			columns = columns.coerceAtLeast(1),
			horizontalSpacing = horizontalSpacing,
			verticalSpacing = verticalSpacing,
			paddingLeft = paddingLeft,
		)
	}

	fun getPosition(): Int = selectedPosition

	fun setPosition(position: Int) {
		if (position < 0) return
		selectedPosition = position
		focusRequestGeneration.value = ++focusGeneration
	}

	fun setOnItemViewSelectedListener(listener: OnItemViewSelectedListener?) {
		selectedListener = listener
	}

	fun setOnItemViewClickedListener(listener: OnItemViewClickedListener?) {
		clickedListener = listener
	}

	fun setOnDirectionalKeyListener(listener: Runnable?) {
		directionalKeyListener = listener
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
		if (wasEmpty && items.isNotEmpty()) {
			setPosition(selectedPosition.coerceAtLeast(0))
		}
	}

	private fun select(holder: ViewHolder, position: Int, item: Any) {
		selectedPosition = position
		if (!selectionNotifications.shouldNotify(position, item)) return
		selectedListener?.onItemSelected(holder, item, null, null)
	}

	private fun click(holder: ViewHolder, position: Int, item: Any) {
		selectedPosition = position
		clickedListener?.onItemClicked(holder, item, null, null)
	}

	@OptIn(ExperimentalFoundationApi::class)
	@Composable
	private fun VerticalBrowseGrid(holder: ViewHolder) {
		val snapshotValue by snapshot.collectAsState()
		val configValue by config.collectAsState()
		val requestGeneration by focusRequestGeneration.collectAsState()
		val packedItems = remember(snapshotValue.revision, configValue.columns, configValue.imageType) {
			packBrowseGridItems(snapshotValue.items, configValue.columns, configValue.imageType)
		}
		val cacheWindow = remember {
			LazyLayoutCacheWindow(
				aheadFraction = 2f,
				behindFraction = 0.5f,
			)
		}
		val initialVisualPosition = remember(packedItems, selectedPosition) {
			findBrowseGridInitialVisualPosition(packedItems, selectedPosition)
		}
		val gridState = rememberLazyGridState(
			cacheWindow = cacheWindow,
			initialFirstVisibleItemIndex = initialVisualPosition,
		)
		val restoreFocusRequester = remember { FocusRequester() }
		val focusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
		var focusedPosition by remember { mutableIntStateOf(selectedPosition.coerceAtLeast(0)) }

		LaunchedEffect(requestGeneration, packedItems.size) {
			if (packedItems.isEmpty()) {
				focusMovePending = false
				return@LaunchedEffect
			}
			val generation = focusGeneration
			try {
				val visualPosition = findBrowseGridInitialVisualPosition(packedItems, selectedPosition)
				focusedPosition = packedItems[visualPosition].adapterPosition
				val key = packedItems[visualPosition].key
				val existingRequester = focusRequesters[key]
				if (generation != focusGeneration) return@LaunchedEffect
				if (existingRequester != null && runCatching { existingRequester.requestFocus() }.getOrDefault(false)) {
					return@LaunchedEffect
				}
				gridState.scrollToItem(visualPosition)
				val requester = snapshotFlow { focusRequesters[key] }.first { it != null }
				if (generation != focusGeneration) return@LaunchedEffect
				runCatching { requireNotNull(requester).requestFocus() }
			} finally {
				if (generation == focusGeneration) focusMovePending = false
			}
		}

		BoxWithConstraints {
			val verticalPadding = 16f
			val viewportHeight = calculateBrowseGridViewportHeight(
				maxHeight = maxHeight.value,
			)

			LazyVerticalGrid(
				columns = GridCells.Fixed(configValue.columns),
				state = gridState,
				modifier = Modifier
					.height(viewportHeight.dp)
					.focusGroup()
					.focusRestorer(restoreFocusRequester),
				contentPadding = PaddingValues(
					horizontal = configValue.paddingLeft.dp,
					vertical = verticalPadding.dp,
				),
				horizontalArrangement = Arrangement.spacedBy(configValue.horizontalSpacing.dp),
				verticalArrangement = Arrangement.spacedBy(configValue.verticalSpacing.dp),
			) {
					items(
						items = packedItems,
						key = { it.key },
						contentType = { it.span },
						span = { GridItemSpan(it.span) },
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
							label = "browse_card_focus",
						)

						val focusModifier = if (entry.adapterPosition == focusedPosition) {
							Modifier.focusRequester(restoreFocusRequester)
						} else {
							Modifier
						}

						CardViewHolderContent(
							item = rowItem,
							focused = focused,
							showInfo = false,
							imageType = configValue.imageType,
							staticHeight = configValue.cardHeight,
							uniformAspect = true,
							fillAvailableWidth = true,
							modifier = focusModifier
								.focusRequester(requester)
								.onPreviewKeyEvent { event ->
									val direction = when (event.key) {
										Key.DirectionUp -> BrowseGridFocusDirection.UP
										Key.DirectionDown -> BrowseGridFocusDirection.DOWN
										Key.DirectionLeft -> BrowseGridFocusDirection.LEFT
										Key.DirectionRight -> BrowseGridFocusDirection.RIGHT
										else -> null
									} ?: return@onPreviewKeyEvent false
									if (event.type == KeyEventType.KeyDown && focusMovePending) {
										return@onPreviewKeyEvent true
									}
									val target = findBrowseGridFocusTarget(packedItems, entry.adapterPosition, direction)
									if (target < 0) {
										return@onPreviewKeyEvent shouldConsumeMissingBrowseGridTarget(direction)
									}
									if (
										event.type == KeyEventType.KeyDown &&
										directionalKeyGuard.tryAccept(event.nativeKeyEvent.eventTime)
									) {
										directionalKeyListener?.run()
										val targetVisualPosition = packedItems.indexOfFirst { it.adapterPosition == target }
										val targetEntry = packedItems.getOrNull(targetVisualPosition)
										requestBrowseGridFocus(
											position = target,
											directRequest = {
												targetEntry?.let { focusRequesters[it.key] }?.requestFocus() == true
											},
											enqueueRequest = {
												focusMovePending = true
												setPosition(it)
											},
										)
									}
									true
								}
								.onFocusChanged { state ->
									focused = state.isFocused
									if (state.isFocused) {
										focusedPosition = entry.adapterPosition
										select(holder, entry.adapterPosition, entry.item)
									}
								}
								.semantics(mergeDescendants = true) {}
								.clickable { click(holder, entry.adapterPosition, entry.item) }
								.zIndex(if (focused) 1f else 0f)
								.scale(scale),
						)
					}
			}
		}
	}
}

internal fun calculateBrowseGridViewportHeight(
	maxHeight: Float,
): Float {
	return maxHeight.coerceAtLeast(0f)
}

internal fun findBrowseGridInitialVisualPosition(
	items: List<PackedBrowseGridItem>,
	selectedPosition: Int,
): Int = items.indexOfFirst { it.adapterPosition == selectedPosition }.coerceAtLeast(0)

internal fun requestBrowseGridFocus(
	position: Int,
	directRequest: () -> Boolean,
	enqueueRequest: (Int) -> Unit,
): Boolean {
	val movedDirectly = runCatching(directRequest).getOrDefault(false)
	if (!movedDirectly) enqueueRequest(position)
	return movedDirectly
}

internal data class PackedBrowseGridItem(
	val adapterPosition: Int,
	val item: Any,
	val span: Int,
	val key: String,
	val row: Int = 0,
	val column: Int = 0,
)

internal fun packBrowseGridItems(items: List<Any>, columns: Int, imageType: ImageType): List<PackedBrowseGridItem> {
	val columnCount = columns.coerceAtLeast(1)
	val source = items.mapIndexed { index, item ->
		val span = if (
			imageType == ImageType.POSTER &&
			item is BaseRowItem &&
			item.browseCardAspectRatio(imageType, true) > 1.25f
		) 2 else 1
		PackedBrowseGridItem(
			adapterPosition = index,
			item = item,
			span = min(span, columnCount),
			key = (item as? BaseRowItem)?.itemId?.toString() ?: "browse-$index",
		)
	}
	val packed = ArrayList<PackedBrowseGridItem>(source.size)
	var remaining = columnCount

	fun place(item: PackedBrowseGridItem) {
		if (item.span > remaining) remaining = columnCount
		packed += item
		remaining -= item.span
		if (remaining == 0) remaining = columnCount
	}

	var index = 0
	while (index < source.size) {
		val item = source[index]
		val next = source.getOrNull(index + 1)
		if (remaining == 1 && item.span == 2 && next?.span == 1) {
			place(next)
			place(item)
			index += 2
		} else {
			place(item)
			index++
		}
	}
	return assignBrowseGridCells(packed, columnCount)
}

private fun assignBrowseGridCells(
	items: List<PackedBrowseGridItem>,
	columns: Int,
): List<PackedBrowseGridItem> {
	var row = 0
	var column = 0
	return items.map { item ->
		if (column + item.span > columns) {
			row++
			column = 0
		}
		val positioned = item.copy(row = row, column = column)
		column += item.span
		if (column == columns) {
			row++
			column = 0
		}
		positioned
	}
}

internal enum class BrowseGridFocusDirection { UP, DOWN, LEFT, RIGHT }

internal fun shouldConsumeMissingBrowseGridTarget(direction: BrowseGridFocusDirection): Boolean =
	direction == BrowseGridFocusDirection.UP || direction == BrowseGridFocusDirection.DOWN

internal class BrowseGridDirectionalKeyGuard {
	private var lastEventTime = Long.MIN_VALUE

	fun tryAccept(eventTime: Long): Boolean {
		if (eventTime == lastEventTime) return false
		lastEventTime = eventTime
		return true
	}
}

internal fun findBrowseGridFocusTarget(
	items: List<PackedBrowseGridItem>,
	currentAdapterPosition: Int,
	direction: BrowseGridFocusDirection,
): Int {
	val current = items.firstOrNull { it.adapterPosition == currentAdapterPosition } ?: return -1
	if (direction == BrowseGridFocusDirection.LEFT || direction == BrowseGridFocusDirection.RIGHT) {
		return items
			.asSequence()
			.filter { candidate -> candidate.row == current.row && candidate.adapterPosition != current.adapterPosition }
			.filter { candidate ->
				if (direction == BrowseGridFocusDirection.LEFT) candidate.column < current.column
				else candidate.column > current.column
			}
			.minByOrNull { candidate -> kotlin.math.abs(candidate.column - current.column) }
			?.adapterPosition ?: -1
	}

	val targetRow = if (direction == BrowseGridFocusDirection.UP) current.row - 1 else current.row + 1
	if (targetRow < 0) return -1
	val currentStart = current.column * 2
	val currentEnd = (current.column + current.span) * 2
	val currentCenter = currentStart + current.span
	return items
		.asSequence()
		.filter { it.row == targetRow }
		.minWithOrNull(
			compareBy<PackedBrowseGridItem> { candidate ->
				val candidateStart = candidate.column * 2
				val candidateEnd = (candidate.column + candidate.span) * 2
				if (candidateStart < currentEnd && candidateEnd > currentStart) 0 else 1
			}.thenBy { candidate ->
				kotlin.math.abs((candidate.column * 2 + candidate.span) - currentCenter)
			}
		)
		?.adapterPosition ?: -1
}
