package org.jellyfin.androidtv.ui.presentation

import android.content.Context
import android.content.res.Configuration
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

enum class ActionButtonSize(
	val widthDp: Int,
	val heightDp: Int,
) {
	SINGLE(126, 39),
	DOUBLE(126, 78),
}

internal val ActionButtonSize.horizontal get() = this == ActionButtonSize.SINGLE

internal fun ActionButtonSize.labelStartMarginDp(hasIcon: Boolean) = if (horizontal && hasIcon) 35 else 0

class ActionButtonPresenter private constructor(
	private val size: ActionButtonSize,
	private val widthDp: Int,
	private val heightDp: Int,
) : Presenter() {
	init {
		require(widthDp > 0 && heightDp > 0) { "Action button dimensions must be positive" }
	}

	@JvmOverloads
	constructor(size: ActionButtonSize = ActionButtonSize.DOUBLE) : this(size, size.widthDp, size.heightDp)

	constructor(widthDp: Int, heightDp: Int) : this(ActionButtonSize.DOUBLE, widthDp, heightDp)

	private class ActionButtonView(
		context: Context,
		private val size: ActionButtonSize,
		private val widthDp: Int,
		private val heightDp: Int,
	) : FrameLayout(context) {
		private val cardWidth get() = dp(widthDp)
		private val cardHeight get() = dp(heightDp)
		private var densityDpi = 0
		private var fontScale = 0f
		private val iconPlate = FrameLayout(context)
		private val icon = ImageView(context)
		private val label = TextView(context)
		private val accent = View(context)

		init {
			isFocusable = true
			isFocusableInTouchMode = true
			descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
			iconPlate.background = GradientDrawable().apply {
				shape = GradientDrawable.RECTANGLE
				setColor(0x24000000)
			}
			addView(iconPlate, LayoutParams(
				0, 0,
				if (size.horizontal) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.TOP or Gravity.START,
			))

			icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
			icon.alpha = 0.92f
			iconPlate.addView(icon, LayoutParams(0, 0, Gravity.CENTER))

			label.ellipsize = TextUtils.TruncateAt.END
			label.includeFontPadding = false
			label.maxLines = if (size.horizontal) 1 else 2
			label.setTextColor(Color.WHITE)
			label.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
			addView(label, LayoutParams(
				LayoutParams.MATCH_PARENT,
				LayoutParams.WRAP_CONTENT,
				if (size.horizontal) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.BOTTOM or Gravity.START,
			))

			accent.background = GradientDrawable(
				GradientDrawable.Orientation.LEFT_RIGHT,
				intArrayOf(
					ContextCompat.getColor(context, R.color.jellyfin_blue),
					ContextCompat.getColor(context, R.color.jellyfin_purple),
				),
			)
			addView(accent, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))
			updateDimensions()
		}

		override fun onConfigurationChanged(newConfig: Configuration) {
			super.onConfigurationChanged(newConfig)
			updateDimensions()
		}

		override fun onAttachedToWindow() {
			super.onAttachedToWindow()
			updateDimensions()
		}

		private fun updateDimensions() {
			val config = resources.configuration
			if (densityDpi == config.densityDpi && fontScale == config.fontScale) return
			densityDpi = config.densityDpi
			fontScale = config.fontScale
			background = ContextCompat.getDrawable(context, R.drawable.channel_card_background)
			minimumWidth = cardWidth
			minimumHeight = cardHeight
			layoutParams = (layoutParams ?: ViewGroup.LayoutParams(cardWidth, cardHeight)).apply {
				width = cardWidth
				height = cardHeight
			}
			setPadding(dp(if (size.horizontal) 8 else 11), dp(if (size.horizontal) 6 else 9), dp(if (size.horizontal) 8 else 11), dp(if (size.horizontal) 6 else 9))
			(iconPlate.background as GradientDrawable).cornerRadius = dp(8).toFloat()
			iconPlate.layoutParams = iconPlate.layoutParams.apply {
				width = dp(if (size.horizontal) 27 else 35)
				height = width
			}
			icon.layoutParams = icon.layoutParams.apply {
				width = dp(if (size.horizontal) 17 else 21)
				height = width
			}
			label.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (size.horizontal) 12f else 11f)
			label.layoutParams = (label.layoutParams as LayoutParams).apply {
				marginStart = dp(size.labelStartMarginDp(iconPlate.visibility == View.VISIBLE))
				bottomMargin = dp(if (size.horizontal) 0 else 8)
			}
			val radius = dp(8).toFloat()
			(accent.background as GradientDrawable).cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
			accent.layoutParams = accent.layoutParams.apply { height = dp(2) }
		}

		override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
			super.onMeasure(
				MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
				MeasureSpec.makeMeasureSpec(cardHeight, MeasureSpec.EXACTLY),
			)
		}

		fun bind(value: GridButton) {
			val hasIcon = value.imageRes != null
			contentDescription = value.text
			label.text = value.text
			iconPlate.visibility = if (hasIcon) View.VISIBLE else View.GONE
			if (!hasIcon) {
				icon.setImageDrawable(null)
			} else {
				icon.setImageResource(requireNotNull(value.imageRes))
			}
			(label.layoutParams as LayoutParams).apply {
				marginStart = dp(size.labelStartMarginDp(hasIcon))
				label.layoutParams = this
			}
		}

		private fun dp(value: Int) = Utils.convertDpToPixel(context, value)
	}

	private class ViewHolder(
		private val buttonView: ActionButtonView,
	) : Presenter.ViewHolder(buttonView) {
		fun bind(value: GridButton) = buttonView.bind(value)
	}

	override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
		val view = ActionButtonView(parent.context, size, widthDp, heightDp)

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
