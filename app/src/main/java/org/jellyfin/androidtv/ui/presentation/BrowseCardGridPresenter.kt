package org.jellyfin.androidtv.ui.presentation

import android.view.KeyEvent
import android.view.View
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.recyclerview.widget.RecyclerView
import org.jellyfin.androidtv.constant.ImageType

abstract class BrowseCardGridPresenter : Presenter() {
	protected data class GridConfig(
		val imageType: ImageType = ImageType.POSTER,
		val cardHeight: Int = 100,
		val spanCount: Int = 1,
		val horizontalSpacing: Int = 0,
		val verticalSpacing: Int = 0,
		val showCardTitles: Boolean = false,
		val paddingStart: Int = 0,
		val paddingEnd: Int = 0,
		val verticalPadding: Int = 0,
	)

	private var selectedPosition = RecyclerView.NO_POSITION
	private val selectionNotifications = GridSelectionNotificationTracker()
	private val directionalKeyGuard = BrowseGridDirectionalKeyGuard()
	private var selectedListener: OnItemViewSelectedListener? = null
	private var clickedListener: OnItemViewClickedListener? = null
	private var directionalKeyListener: Runnable? = null
	private var keyListener: View.OnKeyListener? = null
	protected var gridConfig = GridConfig()
		private set

	fun configure(
		imageType: ImageType,
		cardHeight: Int,
		spanCount: Int,
		horizontalSpacing: Int,
		verticalSpacing: Int,
		showCardTitles: Boolean,
		paddingStart: Int,
		paddingEnd: Int,
		verticalPadding: Int,
	) {
		gridConfig = GridConfig(
			imageType = imageType,
			cardHeight = cardHeight,
			spanCount = spanCount.coerceAtLeast(1),
			horizontalSpacing = horizontalSpacing,
			verticalSpacing = verticalSpacing,
			showCardTitles = showCardTitles,
			paddingStart = paddingStart,
			paddingEnd = paddingEnd,
			verticalPadding = verticalPadding,
		)
		onGridConfigChanged(gridConfig)
	}

	fun getSpanCount(): Int = gridConfig.spanCount

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

	fun getPosition(): Int = selectedPosition

	fun setPosition(position: Int) {
		if (position < 0) return
		selectedPosition = position
		onPositionRequested(position)
	}

	protected fun updatePosition(position: Int) {
		if (position >= 0) selectedPosition = position
	}

	protected fun resetSelectionNotifications() = selectionNotifications.reset()

	protected fun notifyItemSelected(holder: Presenter.ViewHolder?, position: Int, item: Any) {
		updatePosition(position)
		if (selectionNotifications.shouldNotify(position, item)) {
			selectedListener?.onItemSelected(holder, item, null, null)
		}
	}

	protected fun notifyItemClicked(holder: Presenter.ViewHolder?, position: Int, item: Any) {
		updatePosition(position)
		clickedListener?.onItemClicked(holder, item, null, null)
	}

	protected fun notifyDirectionalKey(eventTime: Long): Boolean {
		if (!directionalKeyGuard.tryAccept(eventTime)) return false
		directionalKeyListener?.run()
		return true
	}

	protected fun forwardKey(view: View, keyCode: Int, event: KeyEvent): Boolean =
		keyListener?.onKey(view, keyCode, event) == true

	fun setSpanCount(spanCount: Int) {
		gridConfig = gridConfig.copy(spanCount = spanCount.coerceAtLeast(1))
		onGridConfigChanged(gridConfig)
	}

	protected open fun onGridConfigChanged(config: GridConfig) = Unit

	protected open fun onPositionRequested(position: Int) = Unit
}
