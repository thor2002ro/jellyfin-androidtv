package org.jellyfin.androidtv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.databinding.ViewButtonAlphaPickerBinding

class AlphaPickerView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
	defStyleRes: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {
	var onAlphaSelected: (letter: Char) -> Unit = {}
	private val buttons = mutableListOf<Button>()
	private var vertical = false
	private var gridFocusTargetId = View.NO_ID

	init {
		isFocusable = false
		isFocusableInTouchMode = false
		rebuild()
	}

	fun setVertical(value: Boolean) {
		if (vertical == value) return
		vertical = value
		rebuild()
	}

	fun setGridFocusTargetId(value: Int) {
		gridFocusTargetId = value
		updateGridFocusTargets()
	}

	private fun updateGridFocusTargets() {
		if (gridFocusTargetId == View.NO_ID) return
		buttons.forEach { button ->
			if (vertical) button.nextFocusRightId = gridFocusTargetId
			else button.nextFocusUpId = gridFocusTargetId
		}
	}

	private fun rebuild() {
		buttons.clear()
		removeAllViews()
		val layout = LinearLayout(context).apply {
			orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
		}

		val letters = "#${resources.getString(R.string.byletter_letters)}"
		letters.forEach { letter ->
			val binding = ViewButtonAlphaPickerBinding.inflate(LayoutInflater.from(context), this, false)
			binding.button.apply {
				id = View.generateViewId()
				text = letter.toString()
				minWidth = 0
				minHeight = 0
				minimumWidth = 0
				minimumHeight = 0
				includeFontPadding = false
				textSize = 10f
				setPadding(0, 0, 0, 0)
				setOnClickListener { _ ->
					onAlphaSelected(letter)
				}
			}

			buttons += binding.button
			val buttonLayoutParams = if (vertical) {
				LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
			} else {
				LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
			}
			layout.addView(binding.root, buttonLayoutParams)
		}
		buttons.zipWithNext().forEach { (current, next) ->
			if (vertical) {
				current.nextFocusDownId = next.id
				next.nextFocusUpId = current.id
			} else {
				current.nextFocusRightId = next.id
				next.nextFocusLeftId = current.id
			}
		}
		updateGridFocusTargets()

		val scrollView = if (vertical) {
			ScrollView(context).apply {
				isFocusable = false
				isFillViewport = true
				isVerticalScrollBarEnabled = false
				addView(layout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
			}
		} else {
			HorizontalScrollView(context).apply {
				isFocusable = false
				isFillViewport = true
				isHorizontalScrollBarEnabled = false
				addView(layout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
			}
		}
		addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
	}

	fun focus(letter: Char) {
		buttons
			.firstOrNull { it.text == letter.toString() }
			?.requestFocus()
	}
}
