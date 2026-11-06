package org.jellyfin.playback.media3.exoplayer.subtitle

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

@UnstableApi
fun isSubtitleTimingOffsetSupported(mimeType: String?): Boolean = when (mimeType) {
	MimeTypes.APPLICATION_SUBRIP,
	MimeTypes.TEXT_VTT,
	MimeTypes.TEXT_SSA,
	MimeTypes.APPLICATION_TTML -> true
	else -> false
}

@UnstableApi
fun isSubtitleTimingOffsetSupported(format: Format): Boolean =
	isSubtitleTimingOffsetSupported(format.sampleMimeType)

@UnstableApi
internal fun isSubtitleTimingAdjustmentSupported(
	format: Format,
	isExternal: Boolean,
	isLiveTv: Boolean,
	subtitlesParsedDuringExtraction: Boolean,
	usesLibassOverlay: Boolean,
): Boolean = isSubtitleTimingOffsetSupported(format) &&
	!isLiveTv &&
	(isExternal || !subtitlesParsedDuringExtraction) &&
	(format.sampleMimeType != MimeTypes.TEXT_SSA || !usesLibassOverlay)
