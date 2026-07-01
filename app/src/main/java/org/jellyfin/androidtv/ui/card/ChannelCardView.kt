package org.jellyfin.androidtv.ui.card

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.databinding.ViewCardChannelBinding
import org.jellyfin.androidtv.ui.RecordingIndicatorView
import org.jellyfin.androidtv.ui.copyWithSeriesTimerId
import org.jellyfin.androidtv.ui.copyWithTimerId
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.java.KoinJavaComponent
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ChannelCardView(context: Context) : FrameLayout(context), RecordingIndicatorView {
	private val binding = ViewCardChannelBinding.inflate(LayoutInflater.from(context), this, true)
	private val imageHelper = KoinJavaComponent.get<ImageHelper>(ImageHelper::class.java)
	private var item: BaseItemDto? = null
	private var appliedScale = 0f

	val currentItem: BaseItemDto?
		get() = item

	val currentProgram: BaseItemDto?
		get() = item?.let { currentItem ->
			if (isProgram(currentItem)) currentItem else currentItem.currentProgram
		}

	override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
		super.onSizeChanged(width, height, oldWidth, oldHeight)
		applyContentScale(width, height)
	}

	fun setItem(item: BaseItemDto?) {
		if (item == null) return

		this.item = item
		val program = currentProgram
		val isProgramItem = isProgram(item)

		binding.channelNumber.text = getChannelNumber(item)
		binding.channelName.text = getChannelName(item)
		val channelImageUrl = if (isProgramItem) {
			imageHelper.getChannelPrimaryImageUrl(item, null, ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT)
		} else {
			imageHelper.getPrimaryImageUrl(item, null, ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT)
		}
		binding.channelImage.alpha = if (channelImageUrl == null) CHANNEL_IMAGE_FALLBACK_ALPHA else 1f
		binding.channelImage.load(
			channelImageUrl,
			null,
			ContextCompat.getDrawable(context, R.drawable.ic_tv),
			0.0,
			0,
		)

		val isFavorite = !isProgramItem && item.userData?.isFavorite == true
		binding.favImage.visibility = if (isFavorite) View.VISIBLE else View.GONE

		if (program != null) {
			updateDisplay(program)
			updateRecordingIndicator(program)
		} else {
			binding.program.setText(R.string.no_program_data)
			binding.time.text = ""
			binding.progress.progress = 0
			binding.recIndicator.visibility = View.GONE
		}
	}

	private fun isProgram(item: BaseItemDto): Boolean = when (item.type) {
		BaseItemKind.PROGRAM,
		BaseItemKind.TV_PROGRAM,
		BaseItemKind.LIVE_TV_PROGRAM -> true
		else -> false
	}

	private fun getChannelNumber(item: BaseItemDto) = if (isProgram(item)) {
		item.channelNumber.orEmpty()
	} else {
		item.number.orEmpty()
	}

	private fun getChannelName(item: BaseItemDto) = if (isProgram(item)) {
		item.channelName.orEmpty()
	} else {
		item.name.orEmpty()
	}

	private fun updateRecordingIndicator(program: BaseItemDto) {
		when {
			program.seriesTimerId != null -> {
				binding.recIndicator.setImageResource(
					if (program.timerId != null) R.drawable.ic_record_series_red else R.drawable.ic_record_series
				)
				binding.recIndicator.visibility = View.VISIBLE
			}
			program.timerId != null -> {
				binding.recIndicator.setImageResource(R.drawable.ic_record_red)
				binding.recIndicator.visibility = View.VISIBLE
			}
			else -> {
				binding.recIndicator.visibility = View.GONE
			}
		}
	}

	override fun setRecTimer(id: String?) = updateCurrentProgram { program -> program.copyWithTimerId(id) }

	override fun setRecSeriesTimer(id: String?) = updateCurrentProgram { program -> program.copyWithSeriesTimerId(id) }

	private fun updateCurrentProgram(update: (BaseItemDto) -> BaseItemDto) {
		val currentItem = item ?: return
		val currentProgram = if (isProgram(currentItem)) currentItem else currentItem.currentProgram ?: return
		val updatedProgram = update(currentProgram)

		item = if (isProgram(currentItem)) {
			updatedProgram
		} else {
			currentItem.copy(currentProgram = updatedProgram)
		}
		updateRecordingIndicator(updatedProgram)
	}

	private fun updateDisplay(program: BaseItemDto) {
		binding.program.text = program.name
		val startDate = program.startDate
		val endDate = program.endDate
		if (startDate != null && endDate != null) {
			val timeFormatter = context.getTimeFormatter()
			binding.time.text = "${timeFormatter.format(startDate)}-${timeFormatter.format(endDate)}"

			val now = LocalDateTime.now()
			if (startDate.isBefore(now) && endDate.isAfter(now)) {
				val duration = Duration.between(startDate, endDate)
				val progress = Duration.between(startDate, now)
				binding.progress.progress = ((progress.seconds / duration.seconds.toDouble()) * 100).toInt()
			} else {
				binding.progress.progress = 0
			}
		} else {
			binding.time.text = ""
			binding.progress.progress = 0
		}
	}

	private fun applyContentScale(width: Int, height: Int) {
		if (width <= 0 || height <= 0) return

		val baseWidth = dp(BASE_CARD_WIDTH_DP)
		val baseHeight = dp(BASE_CARD_HEIGHT_DP)
		var scale = min(width / baseWidth.toFloat(), height / baseHeight.toFloat())
		scale = max(MIN_CONTENT_SCALE, min(MAX_CONTENT_SCALE, scale))
		if (abs(scale - appliedScale) < 0.01f) return

		appliedScale = scale

		val padding = scaledDp(10f, scale)
		binding.root.setPadding(padding, padding, padding, padding)

		setSize(binding.favImage, 14f, 14f, scale)
		setSize(binding.recIndicator, 14f, 14f, scale)
		setMargins(binding.recIndicator, 4f, 4f, 0f, 0f, scale)

		setSize(binding.channelImage, 62f, 40f, scale)
		setSize(binding.channelNumber, 62f, 0f, scale)
		setMargins(binding.channelNumber, 0f, 4f, 0f, 0f, scale)

		setMargins(binding.channelName, 10f, 0f, 0f, 0f, scale)
		setTextSize(binding.channelNumber, 13f, scale)
		setTextSize(binding.channelName, 16f, scale)
		setTextSize(binding.program, 13f, scale)
		setTextSize(binding.time, 13f, scale)

		val progressParams = binding.progress.layoutParams
		progressParams.height = scaledDp(5f, scale)
		binding.progress.layoutParams = progressParams
		setMargins(binding.progress, 0f, 4f, 0f, 0f, scale)
		binding.progress.minimumHeight = progressParams.height
	}

	private fun setSize(view: View, widthDp: Float, heightDp: Float, scale: Float) {
		val params = view.layoutParams
		if (widthDp > 0f) params.width = scaledDp(widthDp, scale)
		if (heightDp > 0f) params.height = scaledDp(heightDp, scale)
		view.layoutParams = params
	}

	private fun setMargins(
		view: View,
		startDp: Float,
		topDp: Float,
		endDp: Float,
		bottomDp: Float,
		scale: Float,
	) {
		val layoutParams = view.layoutParams as? RelativeLayout.LayoutParams ?: return
		layoutParams.marginStart = scaledDp(startDp, scale)
		layoutParams.topMargin = scaledDp(topDp, scale)
		layoutParams.marginEnd = scaledDp(endDp, scale)
		layoutParams.bottomMargin = scaledDp(bottomDp, scale)
		view.layoutParams = layoutParams
	}

	private fun setTextSize(view: TextView, sp: Float, scale: Float) {
		view.setTextSize(TypedValue.COMPLEX_UNIT_SP, max(6f, sp * scale))
	}

	private fun scaledDp(value: Float, scale: Float): Int {
		if (value <= 0f) return 0
		return max(1, (dp(value) * scale).roundToInt())
	}

	private fun dp(value: Float) = TypedValue.applyDimension(
		TypedValue.COMPLEX_UNIT_DIP,
		value,
		resources.displayMetrics,
	).roundToInt()

	private companion object {
		const val BASE_CARD_WIDTH_DP = 260f
		const val BASE_CARD_HEIGHT_DP = 128f
		const val CHANNEL_IMAGE_FALLBACK_ALPHA = 0.55f
		const val MIN_CONTENT_SCALE = 0.35f
		const val MAX_CONTENT_SCALE = 1.4f
	}
}
