@file:JvmName("SubtitleStyleColor")

package org.jellyfin.androidtv.ui.player.base

import org.jellyfin.sdk.model.api.VideoRangeType

internal fun selectSubtitleTextColor(
	standardTextColor: Long,
	hdrTextColor: Long,
	videoRangeType: VideoRangeType?,
): Int = if (videoRangeType == VideoRangeType.SDR) {
	standardTextColor.toInt()
} else {
	hdrTextColor.toInt()
}
