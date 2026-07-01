package org.jellyfin.androidtv.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.RelativeLayout
import android.widget.TextView
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.livetv.LiveTvGuide
import org.jellyfin.androidtv.ui.livetv.LiveTvGuideFragment
import org.jellyfin.androidtv.util.Utils

class GuidePagingButton @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : RelativeLayout(context, attrs) {
	private var modernStyle = false
	private var pageRequested = false
	private var pageRequest: (() -> Unit)? = null

	@JvmOverloads
	constructor(
		context: Context,
		guide: LiveTvGuide,
		start: Int,
		label: String,
		modernStyle: Boolean = false,
	) : this(context, guide, start, LiveTvGuideFragment.PAGE_SIZE, label, modernStyle)

	@JvmOverloads
	constructor(
		context: Context,
		guide: LiveTvGuide,
		start: Int,
		max: Int,
		label: String,
		modernStyle: Boolean = false,
	) : this(context) {
		this.modernStyle = modernStyle
		pageRequest = { guide.displayChannels(start, max) }
		val programName = LayoutInflater.from(context)
			.inflate(if (modernStyle) R.layout.guide_paging_button_guide else R.layout.guide_paging_button, this, true)
			.findViewById<TextView>(R.id.programName)
		programName.text = label

		if (modernStyle) applyBackground(false)
		else setBackgroundColor(Utils.getThemeColor(context, R.attr.buttonDefaultNormalBackground))
		isFocusable = true
		setOnClickListener {
			requestPage()
		}
	}

	override fun onFocusChanged(hasFocus: Boolean, direction: Int, previouslyFocused: Rect?) {
		super.onFocusChanged(hasFocus, direction, previouslyFocused)

		if (modernStyle) {
			applyBackground(hasFocus)
		} else {
			setBackgroundColor(
				Utils.getThemeColor(
					context,
					if (hasFocus) android.R.attr.colorAccent else R.attr.buttonDefaultNormalBackground,
				)
			)
		}

		if (hasFocus) {
			post {
				if (hasFocus()) requestPage()
			}
		}
	}

	private fun applyBackground(focused: Boolean) {
		GuideCellBackgrounds.applyButtonBackground(this, focused)
	}

	private fun requestPage() {
		val request = pageRequest ?: return
		if (pageRequested) return
		pageRequested = true
		request()
	}
}
