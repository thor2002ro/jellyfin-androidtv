@file:JvmName("SubtitleStyleColor")

package org.jellyfin.androidtv.ui.player.base

internal fun selectSubtitleTextColor(
	standardTextColor: Long,
	hdrTextColor: Long,
	videoIsHdr: Boolean?,
): Int = if (videoIsHdr == true) hdrTextColor.toInt() else standardTextColor.toInt()
