package org.jellyfin.androidtv.ui

import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.livetv.LiveTvGuide
import org.jellyfin.androidtv.ui.livetv.LiveTvGuideFragment
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.java.KoinJavaComponent

class GuideChannelHeader @JvmOverloads constructor(
	context: Context,
	private val tvGuide: LiveTvGuide,
	var channel: BaseItemDto,
	private val rowHeightDp: Int = LiveTvGuideFragment.GUIDE_ROW_HEIGHT_DP,
	private val modernStyle: Boolean = false,
) : RelativeLayout(context) {
	private val channelImage: AsyncImageView
	private val favImage: ImageView
	private var favorite = channel.userData?.isFavorite == true

	init {
		val view = LayoutInflater.from(context).inflate(
			if (modernStyle) R.layout.channel_header_guide else R.layout.channel_header,
			this,
			false,
		)
		val height = Utils.convertDpToPixel(context, rowHeightDp)
		layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
		view.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
		addView(view)
		isFocusable = true
		findViewById<TextView>(R.id.channelName).text = channel.name
		findViewById<TextView>(R.id.channelNumber).text = channel.number
		channelImage = findViewById(R.id.channelImage)
		favImage = findViewById(R.id.favImage)

		setFavorite(favorite)
		if (modernStyle) {
			applyBackground(false)
		}
	}

	fun loadImage() {
		val imageHelper = KoinJavaComponent.get<ImageHelper>(ImageHelper::class.java)
		channelImage.load(
			imageHelper.getPrimaryImageUrl(channel, null, ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT),
			null,
			null,
			0.0,
			0,
		)
	}

	fun setFavorite(isFavorite: Boolean) {
		favorite = isFavorite
		favImage.visibility = if (favorite) View.VISIBLE else View.GONE
	}

	fun isFavorite() = favorite

	override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
		super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)

		if (gainFocus) {
			tvGuide.setSelectedProgram(this)
		}

		if (modernStyle) {
			applyBackground(gainFocus)
		} else if (gainFocus) {
			setBackgroundColor(Utils.getThemeColor(context, android.R.attr.colorAccent))
		} else {
			background = ContextCompat.getDrawable(context, R.drawable.light_border)
		}
	}

	private fun applyBackground(focused: Boolean) {
		val color = if (focused) {
			Utils.getThemeColor(context, android.R.attr.colorAccent)
		} else {
			ContextCompat.getColor(context, R.color.guide_channel_cell_bg)
		}
		GuideCellBackgrounds.applyCellBackground(this, color, focused)
	}
}
