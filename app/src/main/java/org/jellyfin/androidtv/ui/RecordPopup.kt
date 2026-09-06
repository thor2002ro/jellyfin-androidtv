package org.jellyfin.androidtv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.getQuantityString
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.SeriesTimerInfoDto
import org.koin.java.KoinJavaComponent
import java.util.UUID

class RecordPopup(
	context: Context,
	val lifecycle: Lifecycle,
	private val anchorView: View,
	private val posLeft: Int,
	private val posTop: Int,
	width: Int,
) {
	val mContext: Context = context

	private val popup: PopupWindow
	private var programId: UUID? = null
	private var currentOptions: SeriesTimerInfoDto? = null
	private var selectedView: RecordingIndicatorView? = null
	private var recordSeries = false
	private val title: TextView
	private val timeline: LinearLayout
	private val seriesOptions: View
	private val prePadding: Spinner
	private val postPadding: Spinner
	private val onlyNew: CheckBox
	private val anyTime: CheckBox
	private val anyChannel: CheckBox
	private val okButton: Button
	private val cancelButton: Button
	private val paddingDisplayOptions = arrayListOf(
		context.getString(R.string.lbl_on_schedule),
		context.getQuantityString(R.plurals.minutes, 1),
		context.getQuantityString(R.plurals.minutes, 5),
		context.getQuantityString(R.plurals.minutes, 15),
		context.getQuantityString(R.plurals.minutes, 30),
		context.getQuantityString(R.plurals.minutes, 60),
		context.getQuantityString(R.plurals.minutes, 90),
		context.getQuantityString(R.plurals.hours, 2),
		context.getQuantityString(R.plurals.hours, 3),
	)
	private val paddingValues = arrayListOf(0, 60, 300, 900, 1800, 3600, 5400, 7200, 10800)
	private val customMessageRepository by KoinJavaComponent.inject<CustomMessageRepository>(CustomMessageRepository::class.java)

	init {
		val layout = LayoutInflater.from(context).inflate(R.layout.new_program_record_popup, null)
		val popupHeight = Utils.convertDpToPixel(context, 330)
		popup = PopupWindow(layout, width, popupHeight)
		popup.isFocusable = true
		popup.isOutsideTouchable = true
		popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
		title = layout.findViewById(R.id.title)

		prePadding = layout.findViewById(R.id.prePadding)
		prePadding.adapter = ArrayAdapter(mContext, android.R.layout.simple_spinner_item, paddingDisplayOptions)
		prePadding.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
			override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
				currentOptions = currentOptions?.copyWithPrePaddingSeconds(paddingValues[position])
			}

			override fun onNothingSelected(parent: AdapterView<*>?) = Unit
		}

		postPadding = layout.findViewById(R.id.postPadding)
		postPadding.adapter = ArrayAdapter(mContext, android.R.layout.simple_spinner_item, paddingDisplayOptions)
		postPadding.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
			override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
				currentOptions = currentOptions?.copyWithPostPaddingSeconds(paddingValues[position])
			}

			override fun onNothingSelected(parent: AdapterView<*>?) = Unit
		}

		onlyNew = layout.findViewById(R.id.onlyNew)
		anyChannel = layout.findViewById(R.id.anyChannel)
		anyTime = layout.findViewById(R.id.anyTime)
		seriesOptions = layout.findViewById(R.id.seriesOptions)

		okButton = layout.findViewById(R.id.okButton)
		okButton.setOnClickListener {
			val options = currentOptions ?: return@setOnClickListener
			if (recordSeries) {
				currentOptions = options.copyWithFilters(onlyNew.isChecked, anyChannel.isChecked, anyTime.isChecked)

				updateSeriesTimer(requireNotNull(currentOptions)) {
					popup.dismiss()
					customMessageRepository.pushMessage(CustomMessage.ActionComplete)
					Utils.showToast(mContext, R.string.msg_settings_updated)
				}
			} else {
				val id = programId ?: return@setOnClickListener
				val updated = createProgramTimerInfo(id, options)

				updateTimer(updated) {
					popup.dismiss()
					customMessageRepository.pushMessage(CustomMessage.ActionComplete)
					getLiveTvProgram(id) { program ->
						selectedView?.setRecTimer(program.timerId)
						selectedView?.setRecSeriesTimer(program.seriesTimerId)
					}
					Utils.showToast(mContext, R.string.msg_set_to_record)
				}
			}
		}

		cancelButton = layout.findViewById(R.id.cancelButton)
		cancelButton.setOnClickListener {
			popup.dismiss()
		}

		timeline = layout.findViewById(R.id.timeline)
	}

	fun isShowing() = popup.isShowing

	fun setContent(
		context: Context,
		program: BaseItemDto,
		current: SeriesTimerInfoDto,
		selectedView: RecordingIndicatorView,
		recordSeries: Boolean,
	) {
		programId = program.id
		currentOptions = current
		this.recordSeries = recordSeries
		this.selectedView = selectedView

		title.text = program.name

		TvManager.setTimelineRow(context, timeline, program)

		prePadding.setSelection(getPaddingIndex(current.prePaddingSeconds ?: 0))
		postPadding.setSelection(getPaddingIndex(current.postPaddingSeconds ?: 0))

		if (recordSeries) {
			popup.height = Utils.convertDpToPixel(context, 420)
			seriesOptions.visibility = View.VISIBLE

			anyChannel.isChecked = current.recordAnyChannel == true
			onlyNew.isChecked = current.recordNewOnly == true
			anyTime.isChecked = current.recordAnyTime == true
		} else {
			popup.height = Utils.convertDpToPixel(context, 330)
			seriesOptions.visibility = View.GONE
		}
	}

	private fun getPaddingIndex(seconds: Int): Int {
		for (i in paddingValues.indices) {
			if (paddingValues[i] > seconds) return i - 1
		}

		return 0
	}

	fun show() {
		popup.showAtLocation(anchorView, Gravity.NO_GRAVITY, posLeft, posTop)
		okButton.requestFocus()
	}

	fun dismiss() {
		if (popup.isShowing) popup.dismiss()
	}
}
