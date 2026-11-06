package org.jellyfin.androidtv.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.ui.livetv.LiveTvGuide
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.java.KoinJavaComponent
import java.time.LocalDateTime
import java.util.UUID

class LiveProgramDetailPopup(
	context: Context,
	lifecycleOwner: LifecycleOwner,
	private val tvGuide: LiveTvGuide,
	width: Int,
	private val tuneAction: EmptyResponse?,
) {
	val mContext: Context = context
	val lifecycle: Lifecycle = lifecycleOwner.lifecycle

	private val popup: PopupWindow
	private lateinit var program: BaseItemDto
	private lateinit var selectedProgramView: RecordingIndicatorView
	private val title: TextView
	private val summary: TextView
	private val recordInfo: TextView
	private val timeline: LinearLayout
	private val infoRow: LinearLayout
	private val buttonRow: LinearLayout
	private val similarRow: LinearLayout
	private var firstButton: Button? = null
	private var seriesSettingsButton: Button? = null
	private var anchor: View? = null
	private var posLeft = 0
	private var posTop = 0
	private var recordPopup: RecordPopup? = null
	private var favoriteChannel: BaseItemDto? = null

	init {
		val layout = LayoutInflater.from(context).inflate(R.layout.program_detail_popup, null)
		val popupHeight = Utils.convertDpToPixel(context, 400)
		popup = PopupWindow(layout, width, popupHeight)
		popup.contentView.setViewTreeLifecycleOwner(lifecycleOwner)
		popup.isFocusable = true
		popup.isOutsideTouchable = true
		popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
		popup.elevation = Utils.convertDpToPixel(context, 18).toFloat()
		popup.animationStyle = R.style.WindowAnimation_Fade
		title = layout.findViewById(R.id.title)
		summary = layout.findViewById(R.id.summary)
		recordInfo = layout.findViewById(R.id.recordLine)
		timeline = layout.findViewById(R.id.timeline)
		buttonRow = layout.findViewById(R.id.buttonRow)
		infoRow = layout.findViewById(R.id.infoRow)
		similarRow = layout.findViewById(R.id.similarRow)
	}

	fun isShowing() = popup.isShowing

	@JvmOverloads
	fun setContent(program: BaseItemDto, selectedGridView: RecordingIndicatorView, favoriteChannel: BaseItemDto? = null) {
		this.program = program
		this.favoriteChannel = favoriteChannel
		selectedProgramView = selectedGridView
		title.text = program.name
		buttonRow.removeAllViews()

		summary.text = program.overview.orEmpty()
		summary.gravity = if (program.overview.isNullOrBlank()) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START

		TvManager.setTimelineRow(mContext, timeline, program)

		firstButton = null
		seriesSettingsButton = null
		firstButton = createTuneButton()

		val now = LocalDateTime.now()
		val endDate = program.endDate
		val isChannelOnly = program.startDate == null && program.endDate == null && favoriteChannel != null
		if (isChannelOnly) {
			recordInfo.text = ""
			createDisabledRecordButton()
		} else if (endDate?.isAfter(now) == true) {
			val userRepository = KoinJavaComponent.get<UserRepository>(UserRepository::class.java)
			if (Utils.canManageRecordings(userRepository.currentUser.value)) {
				if (program.timerId != null) {
					val cancel = addButton(buttonRow, R.string.lbl_cancel_recording, R.drawable.ic_record_red, tintIcon = false)
					cancel.setOnClickListener {
						cancelTimer(program.timerId!!) {
							selectedGridView.setRecTimer(null)
							this.program = this.program.copyWithTimerId(null)
							dismiss()
							Utils.showToast(mContext, R.string.msg_recording_cancelled)
						}
					}
					recordInfo.text = if (program.startDate?.isBefore(now) == true) {
						mContext.resources.getString(R.string.msg_recording_now)
					} else {
						mContext.resources.getString(R.string.msg_will_record)
					}
				} else {
					val rec = addButton(buttonRow, R.string.lbl_record, R.drawable.ic_record)
					rec.setOnClickListener {
						recordProgram(program.id) { updatedProgram ->
							this.program = updatedProgram
							selectedProgramView.setRecSeriesTimer(updatedProgram.seriesTimerId)
							selectedProgramView.setRecTimer(updatedProgram.timerId)
							seriesSettingsButton?.visibility = View.VISIBLE
							Utils.showToast(mContext, R.string.msg_set_to_record)
							dismiss()
						}
					}
					recordInfo.text = if (program.seriesTimerId == null) "" else mContext.getString(R.string.lbl_episode_not_record)
				}

				if (Utils.isTrue(program.isSeries)) {
					if (program.seriesTimerId != null) {
						val cancel = addButton(buttonRow, R.string.lbl_cancel_series, R.drawable.ic_record_series_red, tintIcon = false)
						cancel.setOnClickListener {
							AlertDialog.Builder(mContext)
								.setTitle(mContext.resources.getString(R.string.lbl_cancel_series))
								.setMessage(mContext.resources.getString(R.string.msg_cancel_entire_series))
								.setNegativeButton(R.string.lbl_no, null)
								.setPositiveButton(R.string.lbl_yes) { _, _ ->
									cancelSeriesTimer(program.seriesTimerId!!) {
										selectedGridView.setRecSeriesTimer(null)
										this.program = this.program.copyWithSeriesTimerId(null)
										seriesSettingsButton?.visibility = View.GONE
										dismiss()
										Utils.showToast(mContext, R.string.msg_recording_cancelled)
									}
								}
								.show()
						}
					} else {
						val rec = addButton(buttonRow, R.string.lbl_record_series, R.drawable.ic_record_series)
						rec.setOnClickListener {
							recordSeries(program.id) { updatedProgram ->
								this.program = updatedProgram
								selectedProgramView.setRecSeriesTimer(updatedProgram.seriesTimerId)
								selectedProgramView.setRecTimer(updatedProgram.timerId)
								seriesSettingsButton?.visibility = View.VISIBLE
								Utils.showToast(mContext, R.string.msg_set_to_record)
								dismiss()
							}
						}
					}

					seriesSettingsButton = addButton(buttonRow, R.string.lbl_series_settings, R.drawable.ic_settings).apply {
						visibility = if (program.seriesTimerId != null) View.VISIBLE else View.GONE
						setOnClickListener {
							showRecordingOptions(true)
						}
					}
				}
			} else {
				recordInfo.text = ""
				createDisabledRecordButton()
			}
		} else {
			recordInfo.text = mContext.resources.getString(R.string.lbl_program_ended)
			createDisabledRecordButton()
		}
		createFavoriteButton()
		similarRow.visibility = View.GONE
	}

	fun createTuneButton(): Button {
		val tune = addButton(buttonRow, R.string.lbl_tune_to_channel, R.drawable.ic_play)
		tune.setOnClickListener {
			tuneAction?.onResponse()
			popup.dismiss()
		}

		return tune
	}

	private fun createDisabledRecordButton() = addButton(buttonRow, R.string.lbl_record, R.drawable.ic_record, enabled = false)

	fun createFavoriteButton(): ImageButton? {
		val channel = favoriteChannel ?: TvManager.getChannelByID(program.channelId)
		val channelId = channel?.id ?: program.channelId ?: return null
		var isFavorite = channel?.userData?.isFavorite == true

		val favorite = addFavoriteButton(buttonRow, isFavorite)
		updateFavoriteButton(favorite, isFavorite)
		favorite.setOnClickListener {
			val newFavorite = !isFavorite
			setFavorite(channelId, newFavorite) { userData ->
				isFavorite = userData.isFavorite
				favoriteChannel = favoriteChannel?.copy(userData = userData)
				TvManager.updateChannelUserData(channelId, userData)
				updateFavoriteButton(favorite, isFavorite)
				notifyFavoriteChanged(channelId, isFavorite)
			}
		}

		return favorite
	}

	private fun favoriteIcon(isFavorite: Boolean) = if (isFavorite) R.drawable.ic_star else R.drawable.ic_star_outline

	private fun favoriteLabel(isFavorite: Boolean) = if (isFavorite) R.string.lbl_remove_favorite else R.string.lbl_add_favorite

	private fun updateFavoriteButton(button: ImageButton, isFavorite: Boolean) {
		button.contentDescription = mContext.getString(favoriteLabel(isFavorite))
		button.setImageResource(favoriteIcon(isFavorite))
		button.imageTintList = if (isFavorite) null else ContextCompat.getColorStateList(mContext, R.color.program_detail_action_icon)
	}

	private fun notifyFavoriteChanged(channelId: UUID, isFavorite: Boolean) {
		tvGuide.refreshFavorite(channelId, isFavorite)
	}

	private fun addFavoriteButton(layout: LinearLayout, isFavorite: Boolean): ImageButton {
		val button = ImageButton(mContext)
		button.isFocusable = true
		button.background = ContextCompat.getDrawable(mContext, R.drawable.program_detail_action_button)
		button.scaleType = ImageView.ScaleType.CENTER
		button.setPadding(
			Utils.convertDpToPixel(mContext, 14),
			0,
			Utils.convertDpToPixel(mContext, 14),
			0,
		)
		button.contentDescription = mContext.getString(favoriteLabel(isFavorite))
		button.setImageResource(favoriteIcon(isFavorite))
		button.imageTintList = if (isFavorite) null else ContextCompat.getColorStateList(mContext, R.color.program_detail_action_icon)

		val margin = Utils.convertDpToPixel(mContext, 6)
		val params = LinearLayout.LayoutParams(
			Utils.convertDpToPixel(mContext, 56),
			ViewGroup.LayoutParams.MATCH_PARENT,
		).apply {
			marginStart = margin
			marginEnd = margin
		}
		layout.addView(button, params)
		return button
	}

	private fun addButton(
		layout: LinearLayout,
		stringResource: Int,
		imgResource: Int? = null,
		enabled: Boolean = true,
		tintIcon: Boolean = true,
	): Button {
		val button = Button(mContext)
		button.text = mContext.resources.getString(stringResource)
		button.isAllCaps = false
		button.maxLines = 1
		button.ellipsize = TextUtils.TruncateAt.END
		button.gravity = Gravity.CENTER
		button.isEnabled = enabled
		button.alpha = if (enabled) 1f else 0.55f
		button.stateListAnimator = null
		button.minWidth = Utils.convertDpToPixel(mContext, 112)
		button.minimumWidth = button.minWidth
		button.minHeight = 0
		button.minimumHeight = 0
		button.setPadding(
			Utils.convertDpToPixel(mContext, 16),
			0,
			Utils.convertDpToPixel(mContext, 16),
			0,
		)
		button.setBackgroundResource(R.drawable.program_detail_action_button)
		ContextCompat.getColorStateList(mContext, R.color.program_detail_action_text)?.let(button::setTextColor)

		if (imgResource != null) {
			button.setCompoundDrawablesWithIntrinsicBounds(imgResource, 0, 0, 0)
			button.compoundDrawablePadding = Utils.convertDpToPixel(mContext, 8)
			button.compoundDrawableTintList = if (tintIcon) {
				ContextCompat.getColorStateList(mContext, R.color.program_detail_action_icon)
			} else {
				null
			}
		}

		val margin = Utils.convertDpToPixel(mContext, 6)
		val params = LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT,
			ViewGroup.LayoutParams.MATCH_PARENT,
		).apply {
			marginStart = margin
			marginEnd = margin
		}
		layout.addView(button, params)
		return button
	}

	fun show(anchor: View, x: Int, y: Int) {
		this.anchor = anchor
		val (centerX, centerY) = getCenteredPopupPosition()
		posLeft = centerX
		posTop = centerY
		popup.showAtLocation(anchor, Gravity.NO_GRAVITY, centerX, centerY)
		firstButton?.requestFocus()
	}

	private fun getCenteredPopupPosition(): Pair<Int, Int> {
		val displayMetrics = mContext.resources.displayMetrics
		val x = ((displayMetrics.widthPixels - popup.width) / 2).coerceAtLeast(0)
		val y = ((displayMetrics.heightPixels - popup.height) / 2).coerceAtLeast(0)
		return x to y
	}

	fun dismiss() {
		recordPopup?.takeIf { it.isShowing() }?.dismiss()
		if (popup.isShowing) popup.dismiss()
	}

	fun showRecordingOptions(recordSeries: Boolean) {
		val anchorView = anchor ?: return
		if (recordPopup == null) {
			recordPopup = RecordPopup(mContext, lifecycle, anchorView, posLeft, posTop, popup.width)
		}

		getSeriesTimer(program.seriesTimerId!!) { seriesTimer ->
			recordPopup?.setContent(mContext, program, seriesTimer, selectedProgramView, recordSeries)
			recordPopup?.show()
		}
	}
}
