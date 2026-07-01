package org.jellyfin.androidtv.ui.presentation

import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.ui.card.ChannelCardView
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.model.api.BaseItemDto

class ChannelCardPresenter @JvmOverloads constructor(
	private val cardWidthPx: Int = 0,
	private val cardHeightPx: Int = 0,
	private val onLongClick: ((item: Any?, cardView: ChannelCardView) -> Boolean)? = null,
) : Presenter() {
	private companion object {
		const val DEFAULT_CARD_WIDTH_DP = 215
		const val DEFAULT_CARD_HEIGHT_DP = 95
	}

	class ViewHolder(
		private val cardView: ChannelCardView,
	) : Presenter.ViewHolder(cardView) {
		fun bind(item: Any?, onLongClick: ((item: Any?, cardView: ChannelCardView) -> Boolean)?) {
			cardView.setItem(
				when (item) {
					is BaseItemDto -> item
					is BaseItemDtoBaseRowItem -> item.baseItem
					else -> null
				}
			)

			if (onLongClick == null) {
				cardView.setOnLongClickListener(null)
			} else {
				cardView.setOnLongClickListener { onLongClick(item, cardView) }
			}
		}

		fun clearLongClickListener() = cardView.setOnLongClickListener(null)
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

		viewHolder.bind(item, onLongClick)
	}

	override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
		if (viewHolder is ViewHolder) viewHolder.clearLongClickListener()
	}
}
