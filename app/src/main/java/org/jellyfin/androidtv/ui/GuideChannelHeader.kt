package org.jellyfin.androidtv.ui

import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.widget.AbsListView
import android.widget.ImageView
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

class GuideChannelHeader(
	context: Context,
	private val tvGuide: LiveTvGuide,
	var channel: BaseItemDto,
) : RelativeLayout(context) {
	private val channelImage: AsyncImageView
	private val favImage: ImageView

	init {
		val view = LayoutInflater.from(context).inflate(R.layout.channel_header, this, false)
		view.layoutParams = AbsListView.LayoutParams(
			Utils.convertDpToPixel(context, 160),
			Utils.convertDpToPixel(context, LiveTvGuideFragment.GUIDE_ROW_HEIGHT_DP),
		)
		addView(view)
		isFocusable = true
		findViewById<TextView>(R.id.channelName).text = channel.name
		findViewById<TextView>(R.id.channelNumber).text = channel.number
		channelImage = findViewById(R.id.channelImage)
		favImage = findViewById(R.id.favImage)

		if (channel.userData?.isFavorite == true) {
			favImage.visibility = View.VISIBLE
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

	fun refreshFavorite() {
		favImage.visibility = if (channel.userData?.isFavorite == true) View.VISIBLE else View.GONE
	}

	override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
		super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)

		if (gainFocus) {
			setBackgroundColor(Utils.getThemeColor(context, android.R.attr.colorAccent))
			tvGuide.setSelectedProgram(this)
		} else {
			background = ContextCompat.getDrawable(context, R.drawable.light_border)
		}
	}
}
