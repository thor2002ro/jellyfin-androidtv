package org.jellyfin.androidtv.ui.browsing

import android.view.KeyEvent
import android.view.View
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.constant.GridDirection
import org.jellyfin.androidtv.constant.LibraryViewStyle
import org.jellyfin.androidtv.ui.presentation.ComposeBrowseListPresenter
import org.jellyfin.androidtv.ui.presentation.ComposeVerticalGridPresenter
import org.jellyfin.androidtv.ui.presentation.HorizontalGridPresenter
import org.jellyfin.androidtv.ui.presentation.KaleidescapeStylePresenter
import kotlin.math.max
import kotlin.math.min

internal fun createGridPresenter(
	direction: GridDirection,
	viewStyle: LibraryViewStyle = LibraryViewStyle.CARDS
): Presenter {
	if (viewStyle == LibraryViewStyle.DENSE_LIST) return ComposeBrowseListPresenter()
	if (viewStyle == LibraryViewStyle.KALEIDESCAPE_STYLE) return KaleidescapeStylePresenter()
	return if (direction == GridDirection.VERTICAL)
		ComposeVerticalGridPresenter()
	else
		HorizontalGridPresenter()
}

internal fun effectiveNavigationControlsDirection(
	style: LibraryViewStyle,
	cardDirection: GridDirection
): GridDirection {
	if (style == LibraryViewStyle.KALEIDESCAPE_STYLE) return GridDirection.HORIZONTAL
	return if (style == LibraryViewStyle.DENSE_LIST) GridDirection.VERTICAL else cardDirection
}

internal fun shouldDrawBrowseBackground(
	style: LibraryViewStyle,
	direction: GridDirection?
): Boolean {
	return style == LibraryViewStyle.CARDS && direction == GridDirection.HORIZONTAL
}

internal fun getVerticalStatusWidth(
	toolbarBottom: Int,
	counterBottom: Int,
	counterWidth: Int,
	gap: Int
): Int {
	return max(0, counterBottom - counterWidth - toolbarBottom - gap * 2)
}

internal fun configureFooterRotation(counter: View, vertical: Boolean, rise: Int) {
	counter.setRotation(if (vertical) 90f else 0f)
	counter.setPivotX(counter.getWidth().toFloat())
	counter.setPivotY(0f)
	counter.setTranslationY(if (vertical) (counter.getHeight() - rise).toFloat() else 0f)
}

internal fun getGridHostSideMarginDp(direction: GridDirection): Int {
	return if (GridDirection.VERTICAL == direction) 20 else 0
}

internal fun getGridHostEndMarginDp(direction: GridDirection): Int {
	return if (GridDirection.VERTICAL == direction) 26 else 0
}

internal fun getGridHostBottomMarginDp(direction: GridDirection): Int {
	return if (GridDirection.VERTICAL == direction) 0 else 32
}

internal fun pixelsToDp(pixels: Int, density: Float): Int {
	return Math.round(pixels / density)
}

internal fun getHorizontalBrowseCardHeight(rowHeight: Int, showCardTitles: Boolean): Int {
	return max(rowHeight - (if (showCardTitles) 26 else 0), 1)
}

internal fun shouldShowAlphabetPicker(
	pickerDirection: GridDirection,
	gridDirection: GridDirection
): Boolean {
	return pickerDirection == gridDirection
}

internal fun shouldMoveFocusFromGridToToolbar(
	direction: GridDirection,
	action: Int,
	keyCode: Int
): Boolean {
	return GridDirection.HORIZONTAL == direction && action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP
}

internal fun shouldMoveFocusFromGridToHome(
	direction: GridDirection,
	action: Int,
	keyCode: Int
): Boolean {
	return GridDirection.VERTICAL == direction && action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP
}

internal fun shouldConfigureNavigationControlsFocus(direction: GridDirection): Boolean {
	return GridDirection.HORIZONTAL == direction
}

internal fun shouldMoveFocusFromToolbarToHome(
	direction: GridDirection,
	action: Int,
	keyCode: Int
): Boolean {
	return GridDirection.HORIZONTAL == direction && action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP
}

internal fun getSafeSelectedPosition(
	pendingSelectedPosition: Int,
	selectedPosition: Int,
	gridSelectedPosition: Int,
	itemsLoaded: Int
): Int {
	if (itemsLoaded <= 0) return -1

	val position = if (pendingSelectedPosition >= 0)
		pendingSelectedPosition
	else
		if (selectedPosition >= 0)
			selectedPosition
		else max(gridSelectedPosition, 0)

	return min(position, itemsLoaded - 1)
}

internal fun releaseGrid(presenter: Presenter?, viewHolder: Presenter.ViewHolder?) {
	if (presenter != null && viewHolder != null) presenter.onUnbindViewHolder(viewHolder)
}
