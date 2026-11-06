package org.jellyfin.androidtv.ui.presentation

import android.view.View
import androidx.core.view.isVisible
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.RowPresenter
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter

open class CustomListRowPresenter @JvmOverloads constructor(
	private val topPadding: Int? = null
) : ListRowPresenter() {
	private val initialSelectionObservers = mutableMapOf<ViewHolder, Pair<ItemRowAdapter, ObjectAdapter.DataObserver>>()

	init {
		headerPresenter = CustomRowHeaderPresenter()
	}

	override fun isUsingDefaultShadow() = false

	override fun onSelectLevelChanged(holder: RowPresenter.ViewHolder) = Unit

	override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any) {
		super.onBindRowViewHolder(holder, item)

		val view = holder.view?.parent as? View ?: return
		if (topPadding != null) view.setPadding(view.paddingLeft, topPadding, view.paddingRight, view.paddingBottom)

		// Hide header view when the item doesn't have one
		holder.headerViewHolder.view.isVisible = !(item is ListRow && item.headerItem == null)

		val rowHolder = holder as? ViewHolder ?: return
		clearInitialSelectionObserver(rowHolder)

		val rowAdapter = (item as? ListRow)?.adapter as? ItemRowAdapter ?: return
		if (applyInitialSelection(rowHolder, rowAdapter)) return

		val observer = object : ObjectAdapter.DataObserver() {
			override fun onChanged() {
				if (applyInitialSelection(rowHolder, rowAdapter)) {
					clearInitialSelectionObserver(rowHolder)
				}
			}
		}
		rowAdapter.registerObserver(observer)
		initialSelectionObservers[rowHolder] = rowAdapter to observer
	}

	override fun onUnbindRowViewHolder(holder: RowPresenter.ViewHolder) {
		(holder as? ViewHolder)?.let(::clearInitialSelectionObserver)
		super.onUnbindRowViewHolder(holder)
	}

	private fun applyInitialSelection(holder: ViewHolder, adapter: ItemRowAdapter): Boolean {
		val position = adapter.getPendingInitialSelectedPosition() ?: return true
		if (adapter.size() <= position) return false

		holder.gridView.post {
			val pendingPosition = adapter.getPendingInitialSelectedPosition()
			if (pendingPosition != null && adapter.size() > pendingPosition) {
				holder.gridView.selectedPosition = pendingPosition
				adapter.markInitialSelectedPositionApplied()
				clearInitialSelectionObserver(holder)
			}
		}
		return true
	}

	private fun clearInitialSelectionObserver(holder: ViewHolder) {
		val (adapter, observer) = initialSelectionObservers.remove(holder) ?: return
		adapter.unregisterObserver(observer)
	}
}
