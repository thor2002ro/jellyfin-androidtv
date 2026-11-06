@file:JvmName("VideoRangeFormatter")

package org.jellyfin.androidtv.util.sdk

import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType

internal fun VideoRangeType.videoRangeLabels(): List<String> = when (this) {
	VideoRangeType.UNKNOWN -> emptyList()
	VideoRangeType.SDR -> listOf("SDR")
	VideoRangeType.HDR10 -> listOf("HDR10")
	VideoRangeType.HDR10_PLUS -> listOf("HDR10+")
	VideoRangeType.HLG -> listOf("HLG")
	VideoRangeType.DOVI -> listOf("DV P5")
	VideoRangeType.DOVI_WITH_HDR10 -> listOf("DV P8", "HDR10")
	VideoRangeType.DOVI_WITH_HLG -> listOf("DV P8", "HLG")
	VideoRangeType.DOVI_WITH_SDR -> listOf("DV P8", "SDR")
	VideoRangeType.DOVI_WITH_EL -> listOf("DV P7")
	VideoRangeType.DOVI_WITH_HDR10_PLUS -> listOf("DV P8", "HDR10+")
	VideoRangeType.DOVI_WITH_ELHDR10_PLUS -> listOf("DV P7", "HDR10+")
	VideoRangeType.DOVI_INVALID -> listOf("DV invalid")
}

internal fun VideoRangeType.videoRangeLabel(): String? = videoRangeLabels()
	.takeIf { it.isNotEmpty() }
	?.joinToString(" / ")

internal fun VideoRangeType.primaryVideoRangeLabel(): String? = videoRangeLabels().firstOrNull()

internal fun formatVideoRange(
	videoRangeType: VideoRangeType,
	fallbackVideoRange: VideoRange,
): String? = videoRangeType.videoRangeLabel()
	?: fallbackVideoRange.takeUnless { it == VideoRange.UNKNOWN }?.serialName

internal fun String?.toVideoRangeTypeOrNull(): VideoRangeType? {
	if (isNullOrBlank()) return null

	return VideoRangeType.entries.firstOrNull { range ->
		equals(range.name, ignoreCase = true) ||
			equals(range.serialName, ignoreCase = true)
	}
}

internal fun String?.formatVideoRange(): String? {
	if (isNullOrBlank()) return null

	val videoRangeType = toVideoRangeTypeOrNull() ?: return this
	return videoRangeType.videoRangeLabel()
}
