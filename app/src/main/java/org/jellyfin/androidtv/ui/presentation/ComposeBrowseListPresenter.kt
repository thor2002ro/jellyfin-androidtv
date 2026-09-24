@file:Suppress("CyclomaticComplexMethod", "LongMethod")

package org.jellyfin.androidtv.ui.presentation

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.constant.LibraryViewStyle
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.item.ItemCardBaseItemOverlay
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.design.Tokens

internal class BrowseListPresenterState {
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

class ComposeBrowseListPresenter : Presenter() {
	class ViewHolder(val listView: ComposeView) : Presenter.ViewHolder(listView)

	private data class AdapterSnapshot(
		val items: List<Any> = emptyList(),
		val revision: Int = 0,
	)

	private data class ListConfig(
		val horizontalPadding: Int = 16,
		val verticalPadding: Int = 12,
		val itemSpacing: Int = 6,
	)

	private val state = BrowseListPresenterState()
	private val snapshot = MutableStateFlow(AdapterSnapshot())
	private val config = MutableStateFlow(ListConfig())
	private val focusRequestGeneration = MutableStateFlow(0)
	private var adapter: ObjectAdapter? = null
	private var boundViewHolder: ViewHolder? = null
	private val selectionNotifications = GridSelectionNotificationTracker()
	private val adapterSyncGate = GridAdapterSyncGate()
	private var revision = 0
	private var focusGeneration = 0
	private var selectedListener: OnItemViewSelectedListener? = null
	private var clickedListener: OnItemViewClickedListener? = null
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
			layoutParams = ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
			)
		}
		val holder = ViewHolder(composeView)
		boundViewHolder = holder
		composeView.setContent {
			JellyfinTheme {
				BrowseList(holder)
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

	fun configure(horizontalPadding: Int, verticalPadding: Int, itemSpacing: Int) {
		config.value = ListConfig(horizontalPadding, verticalPadding, itemSpacing)
	}

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

	fun setOnDirectionalKeyListener(listener: Runnable?) {
		directionalKeyListener = listener
	}

	fun setOnKeyListener(listener: View.OnKeyListener?) {
		keyListener = listener
	}

	private fun scheduleAdapterSync() {
		val listView = boundViewHolder?.listView ?: return
		adapterSyncGate.request(
			post = { action -> listView.post { action() } },
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
		holder.listView.tag = (item as? BaseRowItem)?.itemId
		if (!selectionNotifications.shouldNotify(position, item)) return
		selectedListener?.onItemSelected(holder, item, null, null)
	}

	private fun click(holder: ViewHolder, position: Int, item: Any) {
		state.setPosition(position)
		clickedListener?.onItemClicked(holder, item, null, null)
	}

	@OptIn(ExperimentalFoundationApi::class)
	@Composable
	private fun BrowseList(holder: ViewHolder) {
		val snapshotValue by snapshot.collectAsState()
		val configValue by config.collectAsState()
		val requestGeneration by focusRequestGeneration.collectAsState()
		val initialIndex = remember(snapshotValue.items.size, state.position) {
			findBrowseListInitialIndex(snapshotValue.items.size, state.position)
		}
		val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
		val restoreFocusRequester = remember { FocusRequester() }
		val focusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
		var focusedPosition by remember { mutableIntStateOf(state.position.coerceAtLeast(0)) }

		LaunchedEffect(requestGeneration, snapshotValue.items.size) {
			if (snapshotValue.items.isEmpty()) return@LaunchedEffect
			val generation = focusGeneration
			val position = findBrowseListInitialIndex(snapshotValue.items.size, state.position)
			focusedPosition = position
			val key = browseListItemKey(snapshotValue.items[position], position)
			val existingRequester = focusRequesters[key]
			if (existingRequester != null && runCatching { existingRequester.requestFocus() }.getOrDefault(false)) {
				return@LaunchedEffect
			}
			listState.scrollToItem(position)
			val requester = snapshotFlow { focusRequesters[key] }.first { it != null }
			if (generation == focusGeneration) runCatching { requireNotNull(requester).requestFocus() }
		}

		LazyColumn(
			state = listState,
			modifier = Modifier
				.fillMaxSize()
				.focusGroup()
				.focusRestorer(restoreFocusRequester),
			contentPadding = PaddingValues(
				horizontal = configValue.horizontalPadding.dp,
				vertical = configValue.verticalPadding.dp,
			),
			verticalArrangement = Arrangement.spacedBy(configValue.itemSpacing.dp),
		) {
			itemsIndexed(
				items = snapshotValue.items,
				key = { index, item -> browseListItemKey(item, index) },
				contentType = { _, item -> (item as? BaseRowItem)?.baseRowType },
			) { index, entry ->
				val rowItem = entry as? BaseRowItem ?: return@itemsIndexed
				val key = browseListItemKey(entry, index)
				val requester = remember(key) { FocusRequester() }
				DisposableEffect(key, requester) {
					focusRequesters[key] = requester
					onDispose {
						if (focusRequesters[key] === requester) focusRequesters.remove(key)
					}
				}
				var focused by remember(key) { mutableStateOf(false) }
				LaunchedEffect(focused, snapshotValue.revision, entry) {
					if (focused) select(holder, index, entry)
				}
				val scale by animateFloatAsState(
					targetValue = if (focused) 1.02f else 1f,
					animationSpec = spring(),
					label = "browse_list_focus",
				)
				val focusModifier = if (index == focusedPosition) {
					Modifier.focusRequester(restoreFocusRequester)
				} else {
					Modifier
				}

				BrowseListRowContent(
					item = rowItem,
					focused = focused,
					onClick = { click(holder, index, entry) },
					modifier = focusModifier
						.focusRequester(requester)
						.scale(scale)
						.onPreviewKeyEvent { event ->
							val nativeEvent = event.nativeKeyEvent
							if (
								shouldForwardBrowseListKey(index, nativeEvent.keyCode) &&
								keyListener?.onKey(holder.listView, nativeEvent.keyCode, nativeEvent) == true
							) {
								return@onPreviewKeyEvent true
							}
							val direction = when (event.key) {
								Key.DirectionUp -> BrowseGridFocusDirection.UP
								Key.DirectionDown -> BrowseGridFocusDirection.DOWN
								Key.DirectionLeft -> BrowseGridFocusDirection.LEFT
								Key.DirectionRight -> BrowseGridFocusDirection.RIGHT
								else -> null
							}
							val directional = direction != null
							if (directional && event.type == KeyEventType.KeyDown && directionalKeyGuard.tryAccept(event.nativeKeyEvent.eventTime)) {
								directionalKeyListener?.run()
							}
							val missingTarget = when (direction) {
								BrowseGridFocusDirection.UP -> index == 0
								BrowseGridFocusDirection.DOWN -> index == snapshotValue.items.lastIndex
								else -> false
							}
							event.type == KeyEventType.KeyDown &&
								direction != null &&
								missingTarget &&
								shouldConsumeMissingBrowseListTarget(direction)
						}
						.onFocusChanged { state ->
							focused = state.hasFocus
							if (state.hasFocus) focusedPosition = index
						},
				)
			}
		}
	}
}

@Composable
internal fun BrowseListRowContent(
	item: BaseRowItem,
	focused: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val title = remember(item, context) { item.getCardName(context).orEmpty() }
	val metadata = remember(item, context) { item.getSubText(context).orEmpty() }
	val background = if (focused) Tokens.Color.colorBlue800 else Tokens.Color.colorBluegrey900.copy(alpha = 0.72f)

	Row(
		modifier = modifier
			.fillMaxWidth()
			.height(BROWSE_LIST_THUMBNAIL_HEIGHT_DP.dp)
			.clip(JellyfinTheme.shapes.small)
			.background(background)
			.combinedClickable(onClick = onClick),
		verticalAlignment = Alignment.CenterVertically,
	) {
		BrowseListThumbnail(
			item = item,
			modifier = Modifier
				.fillMaxHeight()
				.aspectRatio(BROWSE_LIST_THUMBNAIL_ASPECT_RATIO),
		)
		Column(
			modifier = Modifier
				.weight(1f)
				.padding(horizontal = 12.dp),
			verticalArrangement = Arrangement.Center,
		) {
			Text(
				text = title,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				color = Color.White,
			)
			if (metadata.isNotBlank()) {
				Text(
					text = metadata,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					color = Color.White.copy(alpha = 0.7f),
				)
			}
		}
	}
}

@Composable
private fun BrowseListThumbnail(item: BaseRowItem, modifier: Modifier = Modifier) {
	val density = LocalDensity.current
	val image = remember(item) { item.getImage(ImageType.THUMB) ?: item.getImage(ImageType.POSTER) }
	Box(modifier = modifier.background(Tokens.Color.colorBluegrey900)) {
		if (image != null) {
			AsyncImage(
				image = image,
				maxWidth = with(density) { BROWSE_LIST_THUMBNAIL_WIDTH_DP.dp.roundToPx() },
				maxHeight = with(density) { BROWSE_LIST_THUMBNAIL_HEIGHT_DP.dp.roundToPx() },
				aspectRatio = BROWSE_LIST_THUMBNAIL_ASPECT_RATIO,
				modifier = Modifier.fillMaxSize(),
			)
		} else {
			Image(
				painter = painterResource(R.drawable.ic_clapperboard),
				contentDescription = null,
				modifier = Modifier
					.fillMaxSize(BROWSE_LIST_PLACEHOLDER_SCALE)
					.align(Alignment.Center),
			)
		}

		item.baseItem?.let { baseItem ->
			ItemCardBaseItemOverlay(
				item = baseItem,
				streamBadgeItem = (item as? BaseItemDtoBaseRowItem)?.streamBadgeItem ?: baseItem,
				showRemainingTimeBadge = item.showRemainingTimeBadge,
			)
		}
	}
}

private fun browseListItemKey(item: Any, index: Int): String =
	(item as? BaseRowItem)?.itemId?.toString() ?: "browse-list-$index"

internal fun findBrowseListInitialIndex(itemCount: Int, selectedPosition: Int): Int = when {
	itemCount <= 0 -> 0
	selectedPosition < 0 -> 0
	else -> selectedPosition.coerceAtMost(itemCount - 1)
}

internal fun shouldConsumeMissingBrowseListTarget(direction: BrowseGridFocusDirection): Boolean =
	direction == BrowseGridFocusDirection.UP || direction == BrowseGridFocusDirection.DOWN

internal fun shouldForwardBrowseListKey(position: Int, keyCode: Int): Boolean =
	position == 0 && keyCode == KeyEvent.KEYCODE_DPAD_UP

data class BrowseImageRequestSize(
	val widthDp: Int,
	val heightDp: Int,
)

fun resolveBrowseImageRequestSize(
	style: LibraryViewStyle,
	cardWidthDp: Int,
	cardHeightDp: Int,
): BrowseImageRequestSize = if (style == LibraryViewStyle.DENSE_LIST) {
	BrowseImageRequestSize(BROWSE_LIST_THUMBNAIL_WIDTH_DP, BROWSE_LIST_THUMBNAIL_HEIGHT_DP)
} else {
	BrowseImageRequestSize(cardWidthDp, cardHeightDp)
}

private const val BROWSE_LIST_THUMBNAIL_ASPECT_RATIO = 16f / 9f
private const val BROWSE_LIST_PLACEHOLDER_SCALE = 0.4f
const val BROWSE_LIST_THUMBNAIL_WIDTH_DP = 136
const val BROWSE_LIST_THUMBNAIL_HEIGHT_DP = 76
