package org.jellyfin.androidtv.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.LiveTvPreferences
import org.jellyfin.androidtv.ui.livetv.LiveTvGuide
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.androidtv.util.sdk.isNew
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.java.KoinJavaComponent

class ProgramGridCell(
	context: Context,
	private val tvGuide: LiveTvGuide,
	program: BaseItemDto,
	keyListen: Boolean,
) : RelativeLayout(context), RecordingIndicatorView {
	private val programName: TextView
	private val infoRow: LinearLayout
	private val recIndicator: ImageView
	private var backgroundColor = 0
	private var last = false
	private var first = false
	private var program = program

	init {
		val activity = context as Activity
		val view = LayoutInflater.from(activity).inflate(R.layout.program_grid_cell, this, false)
		addView(view)

		isFocusable = true

		programName = findViewById(R.id.programName)
		infoRow = findViewById(R.id.infoRow)
		recIndicator = findViewById(R.id.recIndicator)
		programName.text = program.name

		setCellBackground()

		val startDate = program.startDate
		val endDate = program.endDate
		if (startDate != null && endDate != null && startDate.plusMinutes(1).isBefore(tvGuide.getCurrentLocalStartDate())) {
			programName.text = "<< ${programName.text}"
			val time = TextView(activity).apply {
				typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
				textSize = 12f
				text = context.getTimeFormatter().format(program.startDate)
			}
			infoRow.addView(time)
		}

		val liveTvPreferences = KoinJavaComponent.get<LiveTvPreferences>(LiveTvPreferences::class.java)

		if (
			liveTvPreferences[LiveTvPreferences.showNewIndicator] &&
			program.isNew() &&
			(!liveTvPreferences[LiveTvPreferences.showPremiereIndicator] || !Utils.isTrue(program.isPremiere))
		) {
			addBlockText(activity.getString(R.string.lbl_new), 10, Color.GRAY, R.drawable.dark_green_gradient)
		}

		if (liveTvPreferences[LiveTvPreferences.showPremiereIndicator] && Utils.isTrue(program.isPremiere)) {
			addBlockText(activity.getString(R.string.lbl_premiere), 10, Color.GRAY, R.drawable.dark_green_gradient)
		}

		if (liveTvPreferences[LiveTvPreferences.showRepeatIndicator] && Utils.isTrue(program.isRepeat)) {
			addBlockText(activity.getString(R.string.lbl_repeat), 10, Color.GRAY, androidx.leanback.R.color.lb_default_brand_color)
		}

		val officialRating = program.officialRating
		if (officialRating != null && officialRating != "0") {
			addBlockText(officialRating, 10, Color.BLACK, R.drawable.block_text_bg)
		}

		if (liveTvPreferences[LiveTvPreferences.showHDIndicator] && Utils.isTrue(program.isHd)) {
			addBlockText("HD", 10, Color.BLACK, R.drawable.block_text_bg)
		}

		if (program.seriesTimerId != null) {
			recIndicator.setImageResource(
				if (program.timerId != null) R.drawable.ic_record_series_red else R.drawable.ic_record_series
			)
		} else if (program.timerId != null) {
			recIndicator.setImageResource(R.drawable.ic_record_red)
		}

		if (keyListen) {
			setOnClickListener {
				tvGuide.showProgramOptions()
			}
		}
	}

	private fun addBlockText(text: String, size: Int, textColor: Int, backgroundRes: Int) {
		val view = TextView(context).apply {
			textSize = size.toFloat()
			setTextColor(textColor)
			this.text = " $text "
			setBackgroundResource(backgroundRes)
			layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			).apply {
				setMargins(0, Utils.convertDpToPixel(context, -2), 0, 0)
			}
		}
		infoRow.addView(view)
	}

	fun setCellBackground() {
		val liveTvPreferences = KoinJavaComponent.get<LiveTvPreferences>(LiveTvPreferences::class.java)

		if (liveTvPreferences[LiveTvPreferences.colorCodeGuide]) {
			backgroundColor = when {
				Utils.isTrue(program.isMovie) -> resources.getColor(R.color.guide_movie_bg)
				Utils.isTrue(program.isNews) -> resources.getColor(R.color.guide_news_bg)
				Utils.isTrue(program.isSports) -> resources.getColor(R.color.guide_sports_bg)
				Utils.isTrue(program.isKids) -> resources.getColor(R.color.guide_kids_bg)
				else -> backgroundColor
			}

			setBackgroundColor(backgroundColor)
		}
	}

	override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
		super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)

		if (gainFocus) {
			setBackgroundColor(Utils.getThemeColor(context, android.R.attr.colorAccent))
			tvGuide.setSelectedProgram(this)
		} else {
			setBackgroundColor(backgroundColor)
		}
	}

	fun getProgram(): BaseItemDto = program

	fun setLast() {
		last = true
	}

	fun isLast() = last

	fun setFirst() {
		first = true
	}

	fun isFirst() = first

	override fun setRecTimer(id: String?) {
		program = program.copyWithTimerId(id)
		recIndicator.setImageResource(
			when {
				id != null && program.seriesTimerId != null -> R.drawable.ic_record_series_red
				id != null -> R.drawable.ic_record_red
				program.seriesTimerId != null -> R.drawable.ic_record_series
				else -> R.drawable.blank10x10
			}
		)
	}

	override fun setRecSeriesTimer(id: String?) {
		program = program.copyWithSeriesTimerId(id)
		recIndicator.setImageResource(
			if (id != null) R.drawable.ic_record_series_red else R.drawable.blank10x10
		)
	}
}
