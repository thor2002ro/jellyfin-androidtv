package org.jellyfin.androidtv.util.sdk

import org.jellyfin.androidtv.util.toStreamLanguageBadgeOrNull
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.Locale

internal const val STREAM_BADGE_DISPLAY_LIMIT = 3

internal val streamBadgeItemTypes = setOf(
	BaseItemKind.EPISODE,
	BaseItemKind.MOVIE,
	BaseItemKind.MUSIC_VIDEO,
	BaseItemKind.TRAILER,
	BaseItemKind.VIDEO,
	BaseItemKind.SERIES,
	BaseItemKind.SEASON,
)

internal fun MediaSourceInfo.hasLanguageBadgeStreams() =
	mediaStreams.orEmpty().any { stream ->
		stream.hasLanguageBadge(MediaStreamType.AUDIO) || stream.hasLanguageBadge(MediaStreamType.SUBTITLE)
	}

internal fun MediaStream.hasLanguageBadge(type: MediaStreamType) =
	languageBadgeText(type) != null

internal fun MediaStream.languageBadgeText(type: MediaStreamType): String? =
	if (this.type == type) language.toStreamLanguageBadgeOrNull() else null

internal fun MediaStream.videoBadgeResolutionText(): String? {
	val width = width ?: return null
	val height = height ?: return null

	return videoResolutionName(width, height, isInterlaced)
		?.removeSuffix("p")
		?.removeSuffix("i")
		?.replace("K", "k")
}

@Suppress("MagicNumber")
internal fun videoResolutionName(
	width: Int,
	height: Int,
	interlaced: Boolean = false,
): String? {
	if (width <= 0 || height <= 0) return null

	val suffix = if (interlaced) "i" else "p"
	return when {
		width >= 7600 || height >= 4300 -> "8K"
		width >= 3800 || height >= 2000 -> "4K"
		width >= 2500 || height >= 1400 -> "1440$suffix"
		width >= 1800 || height >= 1000 -> "1080$suffix"
		width >= 1200 || height >= 700 -> "720$suffix"
		width >= 600 || height >= 400 -> "480$suffix"
		else -> "${minOf(width, height)}$suffix"
	}
}

internal fun MediaStream.videoBadgeCodecText(): String? =
	codec
		?.trim()
		?.takeIf { it.isNotEmpty() }
		?.uppercase(Locale.ROOT)

internal fun MediaStream.hasVideoBadgeMetadata() =
	videoBadgeResolutionText() != null || videoBadgeCodecText() != null
