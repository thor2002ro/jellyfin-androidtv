package org.jellyfin.androidtv.util.profile

import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.HdrFormat
import org.jellyfin.androidtv.preference.constant.HdrOverrideMode
import org.jellyfin.sdk.model.api.VideoRangeType

fun UserPreferences.getHdrRangeTypesFor(mode: HdrOverrideMode): Set<VideoRangeType> =
	HdrFormat.entries
		.filter { this[it.preference] == mode }
		.flatMapTo(mutableSetOf()) { it.videoRangeTypes }

fun getUnsupportedHevcVideoRangeWorkarounds(
	mediaTest: MediaCodecCapabilitiesTest,
	forceEnabledHdr: Set<VideoRangeType>,
	forceDisabledHdr: Set<VideoRangeType>,
): Map<VideoRangeType, String> {
	val automaticWorkarounds = getAutomaticHevcVideoRangeWorkarounds(mediaTest)

	return buildMap {
		automaticWorkarounds
			.filterKeys { it !in forceEnabledHdr }
			.forEach { (range, reason) -> put(range, reason) }

		for (range in forceDisabledHdr) {
			put(range, "disabled by HDR override")
		}
	}
}

private fun getAutomaticHevcVideoRangeWorkarounds(
	mediaTest: MediaCodecCapabilitiesTest,
): Map<VideoRangeType, String> = buildMap {
	put(VideoRangeType.DOVI_INVALID, "invalid Dolby Vision range")

	if (!mediaTest.supportsHevcDolbyVisionProfile5()) {
		put(VideoRangeType.DOVI, "codec lacks dvhe.05")
	}

	val canPlayHevcDoviProfile7 = mediaTest.supportsHevcDolbyVisionProfile7() ||
		mediaTest.supportsHevcDolbyVisionEL() ||
		(KnownDefects.unreportedDoviProfile7Support &&
			mediaTest.supportsHevcDolbyVision() &&
			mediaTest.supportsHevcMain10() &&
			mediaTest.supportsHevcHDR10())

	if (!canPlayHevcDoviProfile7) {
		put(VideoRangeType.DOVI_WITH_EL, "codec lacks dvhe.07/EL")
		put(VideoRangeType.DOVI_WITH_ELHDR10_PLUS, "codec lacks dvhe.07/EL")
	}

	if (!mediaTest.supportsHevcDolbyVisionProfile8()) {
		put(VideoRangeType.DOVI_WITH_HDR10, "codec lacks dvhe.08")
		put(VideoRangeType.DOVI_WITH_HDR10_PLUS, "codec lacks dvhe.08")
		put(VideoRangeType.DOVI_WITH_HLG, "codec lacks dvhe.08")
		put(VideoRangeType.DOVI_WITH_SDR, "codec lacks dvhe.08")
	}

	if (!mediaTest.supportsHevcHDR10Plus()) {
		put(VideoRangeType.HDR10_PLUS, "codec lacks HDR10+")

		if (!mediaTest.supportsHevcHDR10()) {
			put(VideoRangeType.HDR10, "codec lacks HDR10")
		}
	}
}
