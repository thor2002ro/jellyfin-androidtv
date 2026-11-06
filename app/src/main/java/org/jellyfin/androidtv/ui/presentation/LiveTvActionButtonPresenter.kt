package org.jellyfin.androidtv.ui.presentation

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.util.Utils

class LiveTvActionButtonPresenter @JvmOverloads constructor(
	private val width: Int = 184,
	private val height: Int = 112,
) : Presenter() {
	private class LiveTvActionButtonView(
		context: Context,
		private val cardWidth: Int,
		private val cardHeight: Int,
	) : FrameLayout(context) {
		private val iconPlate = FrameLayout(context)
		private val icon = ImageView(context)
		private val label = TextView(context)
		private val accent = View(context)

		init {
			isFocusable = true
			isFocusableInTouchMode = true
			descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
			background = ContextCompat.getDrawable(context, R.drawable.channel_card_background)
			minimumWidth = cardWidth
			minimumHeight = cardHeight
			layoutParams = ViewGroup.LayoutParams(cardWidth, cardHeight)
			setPadding(dp(14), dp(12), dp(14), dp(12))

			iconPlate.background = GradientDrawable().apply {
				shape = GradientDrawable.RECTANGLE
				cornerRadius = dp(10).toFloat()
				setColor(0x24000000)
			}
			addView(iconPlate, LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.START))

			icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
			icon.alpha = 0.92f
			iconPlate.addView(icon, LayoutParams(dp(28), dp(28), Gravity.CENTER))

			label.ellipsize = TextUtils.TruncateAt.END
			label.includeFontPadding = false
			label.maxLines = 2
			label.setTextColor(Color.WHITE)
			label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			label.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
			addView(label, LayoutParams(
				LayoutParams.MATCH_PARENT,
				LayoutParams.WRAP_CONTENT,
				Gravity.BOTTOM or Gravity.START,
			).apply {
				bottomMargin = dp(10)
			})

			accent.background = GradientDrawable(
				GradientDrawable.Orientation.LEFT_RIGHT,
				intArrayOf(
					ContextCompat.getColor(context, R.color.jellyfin_blue),
					ContextCompat.getColor(context, R.color.jellyfin_purple),
				),
			).apply {
				cornerRadii = floatArrayOf(
					0f, 0f,
					0f, 0f,
					dp(4).toFloat(), dp(4).toFloat(),
					dp(4).toFloat(), dp(4).toFloat(),
				)
			}
			addView(accent, LayoutParams(LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM))
		}

		override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
			super.onMeasure(
				MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
				MeasureSpec.makeMeasureSpec(cardHeight, MeasureSpec.EXACTLY),
			)
		}

		fun bind(value: GridButton) {
			contentDescription = value.text
			label.text = value.text
			if (value.imageRes == null) {
				icon.setImageDrawable(null)
			} else {
				icon.setImageResource(value.imageRes)
			}
		}

		private fun dp(value: Int) = Utils.convertDpToPixel(context, value)
	}

	private class ViewHolder(
		private val buttonView: LiveTvActionButtonView,
	) : Presenter.ViewHolder(buttonView) {
		fun bind(value: GridButton) = buttonView.bind(value)
	}

	override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
		val view = LiveTvActionButtonView(
			parent.context,
			Utils.convertDpToPixel(parent.context, width),
			Utils.convertDpToPixel(parent.context, height),
		)

		return ViewHolder(view)
	}

	override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
		if (viewHolder !is ViewHolder) return

		when (item) {
			is GridButtonBaseRowItem -> viewHolder.bind(item.gridButton)
			is GridButton -> viewHolder.bind(item)
		}
	}

	override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) = Unit
}
