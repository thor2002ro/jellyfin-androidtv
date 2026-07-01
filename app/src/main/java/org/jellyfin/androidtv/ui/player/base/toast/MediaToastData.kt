package org.jellyfin.androidtv.ui.player.base.toast

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class MediaToastData(
	@DrawableRes val icon: Int,
	val progress: Float? = null,
	@StringRes val text: Int? = null,
)
