package org.jellyfin.androidtv.ui.presentation

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.FocusHighlightHelper
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.Presenter
import androidx.recyclerview.widget.RecyclerView
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem

class HorizontalGridPresenter : BrowseCardGridPresenter() {
	class State {
		private var boundAdapter: Any? = null

		fun bindAdapter(adapter: Any): Boolean {
			if (boundAdapter === adapter) return false
			boundAdapter = adapter
			return true
		}

		fun unbindAdapter() {
			boundAdapter = null
		}
	}

	class ViewHolder(val gridView: HorizontalGridView) : Presenter.ViewHolder(gridView) {
		val itemBridgeAdapter = ItemBridgeAdapter()
	}

	private val state = State()
	private var boundViewHolder: ViewHolder? = null
	private var longClickedListener: ((Any?, View) -> Boolean)? = null

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val grid = HorizontalGridView(parent.context).apply {
			layoutParams = ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
			)
			clipChildren = false
			clipToPadding = false
			isFocusable = true
			isFocusableInTouchMode = true
			setAnimateChildLayout(false)
			setFocusDrawingOrderEnabled(true)
		}
		val holder = ViewHolder(grid)
		boundViewHolder = holder
		applyConfig(holder)
		initialize(holder)
		return holder
	}

	private fun initialize(holder: ViewHolder) {
		FocusHighlightHelper.setupBrowseItemFocusHighlight(
			holder.itemBridgeAdapter,
			FocusHighlight.ZOOM_FACTOR_LARGE,
			false,
		)
		holder.gridView.setOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
			override fun onChildViewHolderSelected(
				parent: RecyclerView,
				child: RecyclerView.ViewHolder?,
				position: Int,
				subposition: Int,
			) {
				val itemHolder = child as? ItemBridgeAdapter.ViewHolder ?: return
				val item = itemHolder.item
				holder.gridView.tag = (item as? BaseRowItem)?.itemId
				notifyItemSelected(itemHolder.viewHolder, position, item)
			}
		})
		holder.itemBridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
			override fun onBind(itemHolder: ItemBridgeAdapter.ViewHolder) {
				val itemView = itemHolder.viewHolder.view
				itemView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
				itemView.setOnClickListener {
					val position = holder.gridView.getChildAdapterPosition(itemHolder.itemView)
					notifyItemClicked(itemHolder.viewHolder, position, itemHolder.item)
				}
				if (longClickedListener != null) {
					itemView.setOnLongClickListener { view ->
						holder.gridView.tag = (itemHolder.item as? BaseRowItem)?.itemId
						longClickedListener?.invoke(itemHolder.item, view) == true
					}
				}
			}

			override fun onUnbind(itemHolder: ItemBridgeAdapter.ViewHolder) {
				itemHolder.viewHolder.view.apply {
					setLayerType(View.LAYER_TYPE_NONE, null)
					setOnClickListener(null)
					if (longClickedListener != null) setOnLongClickListener(null)
				}
			}
		})
		holder.gridView.setOnKeyInterceptListener { event ->
			if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in DIRECTIONAL_KEYS) {
				notifyDirectionalKey(event.eventTime)
			}
			shouldForwardHorizontalGridKey(holder.gridView, getPosition(), event.keyCode) &&
				forwardKey(holder.gridView, event.keyCode, event)
		}
	}

	override fun onBindViewHolder(holder: Presenter.ViewHolder, item: Any?) {
		val viewHolder = holder as ViewHolder
		val objectAdapter = item as ObjectAdapter
		boundViewHolder = viewHolder
		if (state.bindAdapter(objectAdapter) || viewHolder.gridView.adapter !== viewHolder.itemBridgeAdapter) {
			resetSelectionNotifications()
			viewHolder.itemBridgeAdapter.setAdapter(objectAdapter)
			viewHolder.gridView.adapter = viewHolder.itemBridgeAdapter
		}
		getPosition().takeIf { it in 0 until objectAdapter.size() }?.let(viewHolder.gridView::setSelectedPosition)
	}

	override fun onUnbindViewHolder(holder: Presenter.ViewHolder) {
		val viewHolder = holder as ViewHolder
		viewHolder.itemBridgeAdapter.setAdapter(null)
		viewHolder.gridView.adapter = null
		if (boundViewHolder === viewHolder) {
			boundViewHolder = null
			state.unbindAdapter()
		}
	}

	override fun onGridConfigChanged(config: GridConfig) {
		boundViewHolder?.let(::applyConfig)
	}

	private fun applyConfig(holder: ViewHolder) = with(holder.gridView) {
		val density = resources.displayMetrics.density
		setNumRows(gridConfig.spanCount)
		setHorizontalSpacing((gridConfig.horizontalSpacing * density).toInt())
		setVerticalSpacing((gridConfig.verticalSpacing * density).toInt())
		setPadding(
			(gridConfig.paddingStart * density).toInt(),
			(gridConfig.verticalPadding * density).toInt(),
			(gridConfig.paddingEnd * density).toInt(),
			(gridConfig.verticalPadding * density).toInt(),
		)
	}

	override fun onPositionRequested(position: Int) {
		boundViewHolder?.gridView?.setSelectedPosition(position)
	}

	fun setOnItemLongClickedListener(listener: ((Any?, View) -> Boolean)?) {
		longClickedListener = listener
	}

	private companion object {
		val DIRECTIONAL_KEYS = setOf(
			KeyEvent.KEYCODE_DPAD_UP,
			KeyEvent.KEYCODE_DPAD_DOWN,
			KeyEvent.KEYCODE_DPAD_LEFT,
			KeyEvent.KEYCODE_DPAD_RIGHT,
		)
	}
}

private fun shouldForwardHorizontalGridKey(grid: HorizontalGridView, position: Int, keyCode: Int): Boolean {
	if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return true
	val selectedTop = grid.findViewHolderForAdapterPosition(position)?.itemView?.top ?: return true
	return isTopHorizontalGridRow(selectedTop, List(grid.childCount) { grid.getChildAt(it).top })
}

internal fun isTopHorizontalGridRow(selectedTop: Int, visibleChildTops: List<Int>): Boolean =
	selectedTop <= (visibleChildTops.minOrNull() ?: selectedTop)
