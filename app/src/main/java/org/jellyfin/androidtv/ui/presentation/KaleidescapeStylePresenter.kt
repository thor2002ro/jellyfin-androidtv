package org.jellyfin.androidtv.ui.presentation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.PixelCopy
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.Presenter
import androidx.recyclerview.widget.RecyclerView
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.databinding.HorizontalGridBrowseBinding
import org.jellyfin.androidtv.ui.browsing.composable.inforow.BaseItemInfoRow
import org.jellyfin.androidtv.ui.browsing.composable.inforow.InfoRowMediaDetails
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.util.getActivity
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Optional presentation only: the horizontal grid still owns items, focus and pagination. */
class KaleidescapeStylePresenter : HorizontalGridPresenter() {
	override fun createGridContainer(grid: HorizontalGridView): View = KaleidescapeStyleGrid(grid)

	override fun onGridItemSelected(grid: HorizontalGridView, item: Any?) {
		(grid.parent as? KaleidescapeStyleGrid)?.select(item as? BaseRowItem)
	}

	override fun onUnbindViewHolder(holder: Presenter.ViewHolder) {
		(holder.view as KaleidescapeStyleGrid).clear()
		super.onUnbindViewHolder(holder)
	}

	companion object {
		const val FOOTER_HEIGHT_DP = 40
		private data class HostLayout(
			val params: List<Pair<View, RelativeLayout.LayoutParams>>,
			val visibility: List<Pair<View, Int>>,
			val background: Drawable?,
		)

		@JvmStatic
		fun configureHost(binding: HorizontalGridBrowseBinding, enabled: Boolean) {
			val root = binding.root
			val previous = root.getTag(R.id.rowsFragment) as? HostLayout
			if (!enabled) {
				if (previous == null) return
				previous.params.forEach { (view, params) -> view.layoutParams = params }
				previous.visibility.forEach { (view, visibility) -> view.visibility = visibility }
				root.background = previous.background
				root.setTag(R.id.rowsFragment, null)
				return
			}
			if (previous == null) {
				root.setTag(R.id.rowsFragment, HostLayout(
					listOf(binding.rowsFragment, binding.toolBar, binding.alphaPickerHorizontal, binding.counter, binding.statusText).map {
						it to RelativeLayout.LayoutParams(it.layoutParams as RelativeLayout.LayoutParams)
					},
					listOf(binding.title, binding.infoRow, binding.clock, binding.npBug, binding.statusText, binding.counter)
						.map { it to it.visibility },
					root.background,
				))
			}
			val density = root.resources.displayMetrics.density
			root.setBackgroundColor(DETAILS_BACKGROUND_COLOR)
			fun dp(value: Int) = (value * density).toInt()
			listOf(binding.title, binding.infoRow, binding.clock, binding.npBug)
				.forEach { it.visibility = View.GONE }
			binding.statusText.visibility = View.VISIBLE
			binding.statusText.layoutParams = RelativeLayout.LayoutParams(binding.statusText.layoutParams as RelativeLayout.LayoutParams).apply {
				removeRule(RelativeLayout.ALIGN_START)
				removeRule(RelativeLayout.ALIGN_BASELINE)
				addRule(RelativeLayout.ALIGN_PARENT_START)
				addRule(RelativeLayout.ABOVE, binding.alphaPickerHorizontal.id)
				addRule(RelativeLayout.START_OF, binding.toolBar.id)
				marginStart = dp(DETAILS_PADDING_DP)
				marginEnd = dp(GRID_EDGE_PADDING_DP)
			}
			binding.counter.visibility = View.VISIBLE
			binding.counter.layoutParams = RelativeLayout.LayoutParams(binding.counter.layoutParams as RelativeLayout.LayoutParams).apply {
				bottomMargin = 0
			}
			binding.rowsFragment.layoutParams = RelativeLayout.LayoutParams(binding.rowsFragment.layoutParams as RelativeLayout.LayoutParams).apply {
				removeRule(RelativeLayout.BELOW)
				addRule(RelativeLayout.ALIGN_PARENT_TOP)
				topMargin = 0
				bottomMargin = dp(FOOTER_HEIGHT_DP)
				marginStart = 0
				marginEnd = 0
			}
			binding.toolBar.layoutParams = RelativeLayout.LayoutParams(binding.toolBar.layoutParams as RelativeLayout.LayoutParams).apply {
				removeRule(RelativeLayout.BELOW)
				removeRule(RelativeLayout.START_OF)
				removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
				addRule(RelativeLayout.ALIGN_PARENT_END)
				addRule(RelativeLayout.ABOVE, binding.counter.id)
				width = RelativeLayout.LayoutParams.WRAP_CONTENT
				topMargin = 0
				bottomMargin = 0
				marginEnd = dp(DETAILS_PADDING_DP)
			}
			binding.alphaPickerHorizontal.layoutParams = RelativeLayout.LayoutParams(
				binding.alphaPickerHorizontal.layoutParams as RelativeLayout.LayoutParams,
			).apply {
				height = dp(24)
				removeRule(RelativeLayout.END_OF)
				addRule(RelativeLayout.ALIGN_PARENT_START)
				addRule(RelativeLayout.START_OF, binding.toolBar.id)
				marginStart = dp(DETAILS_PADDING_DP)
				marginEnd = dp(GRID_EDGE_PADDING_DP)
			}
		}

		@JvmStatic
		fun configureFooterFocus(binding: HorizontalGridBrowseBinding, grid: View) {
			for (index in 0 until binding.toolBar.childCount) {
				binding.toolBar.getChildAt(index).apply {
					if (id == View.NO_ID) id = View.generateViewId()
					nextFocusUpId = grid.id
				}
			}
		}
	}
}

private class KaleidescapeStyleGrid(private val grid: HorizontalGridView) : FrameLayout(grid.context) {
	private val details = KaleidescapeStyleDetails(context)
	private var selectedItem: BaseRowItem? = null
	private val updateDetails = Runnable { showDetails() }

	init {
		layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
		addView(grid)
		addView(details)
		grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
			override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = scheduleDetails()
		})
		grid.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleDetails() }
	}

	fun select(item: BaseRowItem?) {
		selectedItem = item
		scheduleDetails()
	}

	override fun requestChildFocus(child: View, focused: View) {
		super.requestChildFocus(child, focused)
		scheduleDetails()
	}

	private fun scheduleDetails() {
		details.hide()
		removeCallbacks(updateDetails)
		// Capture only after scrolling settles, never for every animation frame.
		postDelayed(updateDetails, DETAILS_DELAY_MS)
	}

	private fun showDetails() {
		val item = selectedItem ?: return
		if (!grid.hasFocus() || item.baseItem == null) return
		val holder = grid.findViewHolderForAdapterPosition(grid.selectedPosition) as? ItemBridgeAdapter.ViewHolder ?: return
		if (holder.item !== item) return
		val poster = Rect().also {
			if (!holder.viewHolder.view.getGlobalVisibleRect(it)) return
			val location = IntArray(2).also(::getLocationOnScreen)
			it.offset(-location[0], -location[1])
		}
		val density = resources.displayMetrics.density
		val contentScale = (poster.height() / density / 160f).coerceIn(0.6f, 1f)
		val padding = (DETAILS_PADDING_DP * density * contentScale).toInt()
		val bounds = kaleidescapeStyleDetailsBounds(
			width, height, poster.left, poster.right, poster.top,
			min((width * DETAILS_WIDTH_FRACTION).toInt(), (poster.height() * 2.3f).toInt()), poster.height(),
			-padding,
		) ?: return
		val onLeft = bounds.left < poster.left
		details.setPadding(if (onLeft) padding else padding * 2, padding, if (onLeft) padding * 2 else padding, padding)
		details.layoutParams = LayoutParams(bounds.width, bounds.height).apply {
			leftMargin = bounds.left
			topMargin = bounds.top
		}
		details.bind(item, contentScale)
		details.capture(grid, bounds)
	}

	override fun dispatchDraw(canvas: Canvas) {
		super.dispatchDraw(canvas)
		if (details.visibility != View.VISIBLE || !grid.hasFocus()) return
		val holder = grid.findViewHolderForAdapterPosition(grid.selectedPosition) as? ItemBridgeAdapter.ViewHolder ?: return
		// Redraw only the overlap above the flyout, preserving the card's rounded edge and focus border.
		val save = canvas.save()
		canvas.clipRect(details.left, details.top, details.right, details.bottom)
		val card = holder.viewHolder.view
		fun transform(view: View) {
			val parent = view.parent as? View ?: return
			if (parent !== this) transform(parent)
			canvas.translate((view.left - parent.scrollX).toFloat(), (view.top - parent.scrollY).toFloat())
			canvas.concat(view.matrix)
		}
		transform(card)
		card.draw(canvas)
		canvas.restoreToCount(save)
	}

	fun clear() {
		removeCallbacks(updateDetails)
		selectedItem = null
		details.clear()
	}

	override fun onDetachedFromWindow() {
		clear()
		super.onDetachedFromWindow()
	}
}

internal data class KaleidescapeStyleBounds(val left: Int, val top: Int, val width: Int, val height: Int)

internal fun kaleidescapeStyleDetailsBounds(
	viewportWidth: Int, viewportHeight: Int, posterLeft: Int, posterRight: Int, posterTop: Int,
	desiredWidth: Int, desiredHeight: Int, gap: Int,
): KaleidescapeStyleBounds? {
	val leftSpace = (posterLeft - gap).coerceAtLeast(0)
	val rightSpace = (viewportWidth - posterRight - gap).coerceAtLeast(0)
	val onLeft = leftSpace >= desiredWidth || leftSpace > rightSpace
	val width = min(desiredWidth, if (onLeft) leftSpace else rightSpace)
	val height = min(desiredHeight, viewportHeight)
	if (width <= 0 || height <= 0) return null
	return KaleidescapeStyleBounds(
		if (onLeft) posterLeft - gap - width else posterRight + gap,
		posterTop.coerceIn(0, viewportHeight - height), width, height,
	)
}

private class KaleidescapeStyleDetails(context: Context) : LinearLayout(context) {
	private val title = TextView(context).apply {
		setTextColor(Color.WHITE)
		textSize = 20f
		setTypeface(null, Typeface.BOLD)
		maxLines = 2
		ellipsize = TextUtils.TruncateAt.END
	}
	private val info = KaleidescapeStyleMetadata(context)
	private val genres = TextView(context).apply {
		setTextColor(Color.WHITE)
		textSize = 14f
		includeFontPadding = false
		maxLines = 2
		ellipsize = TextUtils.TruncateAt.END
	}
	private val overview = TextView(context).apply {
		setTextColor(Color.WHITE)
		textSize = 14f
		includeFontPadding = false
	}
	private val description = ScrollView(context).apply {
		isFocusable = false
		descendantFocusability = FOCUS_BLOCK_DESCENDANTS
		isVerticalScrollBarEnabled = false
		addView(overview, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
	}
	private var descriptionAnimator: ValueAnimator? = null
	private var backdrop: Bitmap? = null
	private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 26 }
	private val backdropBounds = Rect()
	private var captureGeneration = 0

	init {
		orientation = VERTICAL
		descendantFocusability = FOCUS_BLOCK_DESCENDANTS
		isFocusable = false
		visibility = View.INVISIBLE
		setWillNotDraw(false)
		val padding = (DETAILS_PADDING_DP * resources.displayMetrics.density).toInt()
		setPadding(padding, padding, padding, padding)
		addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
			bottomMargin = (FOOTER_BOTTOM_MARGIN_DP * resources.displayMetrics.density).toInt()
		})
		addView(genres, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
			bottomMargin = (FOOTER_BOTTOM_MARGIN_DP * resources.displayMetrics.density).toInt()
		})
		addView(description, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
		addView(info, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
			topMargin = (FOOTER_BOTTOM_MARGIN_DP * resources.displayMetrics.density).toInt()
		})
		overview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> startDescriptionScroll() }
		description.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> startDescriptionScroll() }
	}

	fun bind(item: BaseRowItem, contentScale: Float) {
		title.textSize = 20f * contentScale
		genres.textSize = 14f * contentScale
		overview.textSize = 14f * contentScale
		val spacing = (FOOTER_BOTTOM_MARGIN_DP * resources.displayMetrics.density * contentScale).toInt()
		(title.layoutParams as LayoutParams).bottomMargin = spacing
		(genres.layoutParams as LayoutParams).bottomMargin = spacing
		(info.layoutParams as LayoutParams).topMargin = spacing
		info.contentScale = contentScale
		title.text = item.getName(context)
		genres.text = item.baseItem?.genres?.joinToString(" / ").orEmpty()
		genres.visibility = if (genres.text.isBlank()) View.GONE else View.VISIBLE
		// The compact footer uses the year, leaving room for ratings without wrapping badges.
		info.item = item.baseItem?.let { baseItem ->
			baseItem.copy(premiereDate = null, productionYear = baseItem.productionYear ?: baseItem.premiereDate?.year)
		}
		val badgeItem = (item as? BaseItemDtoBaseRowItem)?.streamBadgeItem ?: item.baseItem
		info.mediaSource = badgeItem?.mediaSources?.firstOrNull()
		overview.text = item.baseItem?.overview.orEmpty()
	}

	private fun startDescriptionScroll() {
		stopDescriptionScroll()
		description.scrollTo(0, 0)
		val distance = overview.height - description.height
		if (!isShown || distance <= 0 || description.height <= 0) return
		val scrollDuration = (distance / resources.displayMetrics.density / DESCRIPTION_SPEED_DP_PER_SECOND * 1000).toLong()
		val cycleDuration = DESCRIPTION_PAUSE_MS * 2 + scrollDuration
		descriptionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = cycleDuration
			repeatCount = ValueAnimator.INFINITE
			interpolator = LinearInterpolator()
			addUpdateListener { animator ->
				val elapsed = animator.animatedFraction * cycleDuration
				val progress = ((elapsed - DESCRIPTION_PAUSE_MS) / scrollDuration).coerceIn(0f, 1f)
				description.scrollTo(0, (distance * progress).toInt())
			}
			start()
		}
	}

	private fun stopDescriptionScroll() {
		descriptionAnimator?.cancel()
		descriptionAnimator = null
	}

	private fun show() {
		visibility = View.VISIBLE
		startDescriptionScroll()
	}

	fun capture(grid: View, bounds: KaleidescapeStyleBounds) {
		// A small filtered snapshot softens the posters on older TVs without a full-screen blur pass.
		// PixelCopy also supports hardware-backed poster images, unlike drawing into a software Canvas.
		val window = context.getActivity()?.window
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || window == null) {
			show()
			return
		}
		val sampleSize = (8 * resources.displayMetrics.density).coerceAtLeast(1f)
		val bitmapWidth = max(1, (bounds.width / sampleSize).toInt())
		val bitmapHeight = max(1, (bounds.height / sampleSize).toInt())
		val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
		val location = IntArray(2).also(grid::getLocationInWindow)
		val source = Rect(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height).apply {
			offset(location[0], location[1])
		}
		val generation = ++captureGeneration
		try {
			PixelCopy.request(window, source, bitmap, { result ->
				if (generation == captureGeneration && isAttachedToWindow && grid.hasFocus()) {
					backdrop = bitmap.takeIf { result == PixelCopy.SUCCESS }
					show()
					invalidate()
				}
			}, Handler(Looper.getMainLooper()))
		} catch (_: IllegalArgumentException) {
			// The window may have lost its surface while navigating away.
			backdrop = null
			show()
		}
	}

	override fun onDraw(canvas: Canvas) {
		canvas.drawColor(if (backdrop == null) DETAILS_FALLBACK_COLOR else DETAILS_BACKGROUND_COLOR)
		backdropBounds.set(0, 0, width, height)
		backdrop?.let { canvas.drawBitmap(it, null, backdropBounds, backdropPaint) }
		super.onDraw(canvas)
	}

	fun clear() {
		hide()
		info.item = null
		info.mediaSource = null
		backdrop = null
	}

	fun hide() {
		captureGeneration++
		stopDescriptionScroll()
		visibility = View.INVISIBLE
	}

	override fun onWindowVisibilityChanged(visibility: Int) {
		super.onWindowVisibilityChanged(visibility)
		if (visibility == View.VISIBLE) startDescriptionScroll() else stopDescriptionScroll()
	}
}

private class KaleidescapeStyleMetadata(context: Context) : AbstractComposeView(context) {
	var item by mutableStateOf<BaseItemDto?>(null)
	var mediaSource by mutableStateOf<MediaSourceInfo?>(null)
	var contentScale by mutableStateOf(1f)

	init {
		isFocusable = false
		descendantFocusability = FOCUS_BLOCK_DESCENDANTS
	}

	@Composable
	override fun Content() {
		val currentItem = item ?: return
		val density = LocalDensity.current
		CompositionLocalProvider(LocalDensity provides Density(density.density * contentScale, density.fontScale)) {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Column(Modifier.height(DETAILS_INFO_HEIGHT_DP.dp)) {
					BaseItemInfoRow(currentItem, mediaSource = null, includeRuntime = true)
				}
				mediaSource?.let { source ->
					Layout(content = {
						Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
							InfoRowMediaDetails(source)
						}
					}) { measurables, constraints ->
						// Keep the shared badges on one line, shrinking only when their natural width exceeds the flyout.
						val row = measurables.single().measure(Constraints())
						val scale = min(1f, constraints.maxWidth.toFloat() / row.width.coerceAtLeast(1))
						layout(constraints.constrainWidth((row.width * scale).roundToInt()), constraints.constrainHeight((row.height * scale).roundToInt())) {
							row.placeWithLayer(0, 0) {
								scaleX = scale
								scaleY = scale
								transformOrigin = TransformOrigin(0f, 0f)
							}
						}
					}
				}
			}
		}
	}
}

private const val DETAILS_DELAY_MS = 160L
private const val DETAILS_WIDTH_FRACTION = 0.40f
private const val DETAILS_PADDING_DP = 12
private const val GRID_EDGE_PADDING_DP = 8
private const val FOOTER_BOTTOM_MARGIN_DP = 4
private const val DETAILS_INFO_HEIGHT_DP = 24
private const val DESCRIPTION_PAUSE_MS = 2000L
private const val DESCRIPTION_SPEED_DP_PER_SECOND = 12f
private const val DETAILS_BACKGROUND_COLOR = 0xff08192d.toInt()
private const val DETAILS_FALLBACK_COLOR = 0xe608192d.toInt()
