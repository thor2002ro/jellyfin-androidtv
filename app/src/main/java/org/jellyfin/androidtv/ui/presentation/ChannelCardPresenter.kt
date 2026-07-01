package org.jellyfin.androidtv.ui.presentation

import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.ui.card.ChannelCardView
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.model.api.BaseItemDto

class ChannelCardPresenter(
	private val cardWidthPx: Int = 0,
	private val cardHeightPx: Int = 0,
) : Presenter() {
	private companion object {
		const val DEFAULT_CARD_WIDTH_DP = 215
		const val DEFAULT_CARD_HEIGHT_DP = 95
	}

	class ViewHolder(
		private val cardView: ChannelCardView,
	) : Presenter.ViewHolder(cardView) {
		fun setItem(item: Any?) = cardView.setItem(
			when (item) {
				is BaseItemDto -> item
				is BaseItemDtoBaseRowItem -> item.baseItem
				else -> null
			}
		)
	}

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val width = cardWidthPx.takeIf { it > 0 } ?: Utils.convertDpToPixel(parent.context, DEFAULT_CARD_WIDTH_DP)
		val height = cardHeightPx.takeIf { it > 0 } ?: Utils.convertDpToPixel(parent.context, DEFAULT_CARD_HEIGHT_DP)
		val view = ChannelCardView(parent.context).apply {
			isFocusable = true
			isFocusableInTouchMode = true
			layoutParams = ViewGroup.LayoutParams(
				width,
				height,
			)
		}

		return ViewHolder(view)
	}

	override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
		if (viewHolder !is ViewHolder) return

		viewHolder.setItem(item)
	}

	override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) = Unit
}
