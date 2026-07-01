package org.jellyfin.androidtv.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.util.Utils

object GuideCellBackgrounds {
	fun applyCellBackground(view: View, color: Int, focused: Boolean) {
		val colors = when {
			focused -> intArrayOf(
				ContextCompat.getColor(view.context, R.color.card_focus_gradient_start),
				ContextCompat.getColor(view.context, R.color.card_focus_gradient_center),
				ContextCompat.getColor(view.context, R.color.card_focus_gradient_end),
			)
			color == ContextCompat.getColor(view.context, R.color.guide_program_cell_bg) ||
				color == ContextCompat.getColor(view.context, R.color.guide_channel_cell_bg) -> intArrayOf(
				ContextCompat.getColor(view.context, R.color.card_neutral_gradient_start),
				ContextCompat.getColor(view.context, R.color.card_neutral_gradient_center),
				ContextCompat.getColor(view.context, R.color.card_neutral_gradient_end),
			)
			else -> intArrayOf(
				ColorUtils.blendARGB(color, Color.WHITE, 0.08f),
				ColorUtils.blendARGB(color, Color.BLACK, 0.18f),
			)
		}

		view.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
			cornerRadius = Utils.convertDpToPixel(view.context, 6).toFloat()
			setStroke(
				view.resources.getDimensionPixelSize(R.dimen.card_focus_stroke_width),
				ContextCompat.getColor(view.context, if (focused) R.color.card_focus_stroke else R.color.guide_grid_line),
			)
		}
	}

	fun applyButtonBackground(view: View, focused: Boolean) {
		view.background = GradientDrawable().apply {
			cornerRadius = Utils.convertDpToPixel(view.context, 6).toFloat()
			setColor(
				if (focused) {
					Utils.getThemeColor(view.context, android.R.attr.colorAccent)
				} else {
					ContextCompat.getColor(view.context, R.color.guide_button_bg)
				}
			)
			setStroke(
				Utils.convertDpToPixel(view.context, if (focused) 2 else 1),
				ContextCompat.getColor(view.context, if (focused) R.color.white else R.color.guide_grid_line),
			)
		}
	}
}
