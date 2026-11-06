package org.jellyfin.androidtv.preference.constant

enum class PlaybackResolution(
	val label: String,
	val maxWidth: Int?,
	val maxHeight: Int?,
) {
	NATIVE("Native", null, null),
	UHD_4K("4K", 3840, 2160),
	FULL_HD_1080("1080p", 1920, 1080),
	HD_720("720p", 1280, 720),
	SD_480("480p", 854, 480);

	fun capWidth(width: Int) = maxWidth?.let { width.coerceAtMost(it) } ?: width

	fun capHeight(height: Int) = maxHeight?.let { height.coerceAtMost(it) } ?: height
}
