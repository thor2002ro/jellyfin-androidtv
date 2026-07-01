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
		val topColor = ColorUtils.blendARGB(color, Color.WHITE, if (focused) 0.22f else 0.08f)
		val bottomColor = ColorUtils.blendARGB(color, Color.BLACK, if (focused) 0.12f else 0.18f)
		view.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(topColor, bottomColor)).apply {
			cornerRadius = Utils.convertDpToPixel(view.context, 6).toFloat()
			setStroke(
				Utils.convertDpToPixel(view.context, if (focused) 2 else 1),
				ContextCompat.getColor(view.context, if (focused) R.color.white else R.color.guide_grid_line),
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
