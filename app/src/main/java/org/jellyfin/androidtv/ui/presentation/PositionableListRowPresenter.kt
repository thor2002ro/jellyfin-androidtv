package org.jellyfin.androidtv.ui.presentation

import android.view.KeyEvent
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.RowPresenter
import timber.log.Timber

class PositionableListRowPresenter : CustomListRowPresenter {
	private var viewHolder: ViewHolder? = null
	private val trapFocus: Boolean

	constructor() : this(padding = null, trapFocus = false)
	constructor(padding: Int?) : this(padding, trapFocus = false)
	constructor(padding: Int? = null, trapFocus: Boolean = false) : super(padding) {
		this.trapFocus = trapFocus
	}

	init {
		shadowEnabled = false
	}

	override fun isUsingDefaultShadow() = false

	override fun onSelectLevelChanged(holder: RowPresenter.ViewHolder) = Unit

	override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any) {
		super.onBindRowViewHolder(holder, item)
		if (holder !is ViewHolder) return

		viewHolder = holder
		val grid = holder.gridView
		if (trapFocus) {
			val adapter = (item as? ListRow)?.adapter
			// Keep focus inside the row at either boundary.
			grid.setOnKeyInterceptListener { event ->
				val position = grid.selectedPosition
				val adapterSize = adapter?.size() ?: 0
				when (event.keyCode) {
					KeyEvent.KEYCODE_DPAD_LEFT -> position <= 0
					KeyEvent.KEYCODE_DPAD_RIGHT -> adapterSize > 0 && position >= adapterSize - 1
					else -> false
				}
			}
		}
	}

	var position: Int
		get() = viewHolder?.gridView?.selectedPosition ?: -1
		set(value) {
			Timber.d("Setting position to $value")
			viewHolder?.gridView?.selectedPosition = value
		}
}
