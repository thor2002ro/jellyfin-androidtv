package org.jellyfin.androidtv.util.profile

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import org.jellyfin.androidtv.preference.constant.PlaybackResolution
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.VideoRangeType
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile

/** Extra software-only claims, not a replacement for measured platform decoder capabilities. */
@OptIn(UnstableApi::class)
internal fun DeviceProfile.withFfmpegVideo(
	mediaTest: MediaCodecCapabilitiesTest,
	maxResolution: PlaybackResolution,
	userAVCLevel: Int?,
	userHEVCLevel: Int?,
): DeviceProfile {
	val missingCodecs = buildMap {
		if (!mediaTest.supportsAVC()) put("h264", MimeTypes.VIDEO_H264)
		if (!mediaTest.supportsHevc()) put("hevc", MimeTypes.VIDEO_H265)
		if (!mediaTest.supportsAV1()) put("av1", MimeTypes.VIDEO_AV1)
		if (!mediaTest.supportsVp8()) put("vp8", MimeTypes.VIDEO_VP8)
		if (!mediaTest.supportsVp9()) put("vp9", MimeTypes.VIDEO_VP9)
		if (!mediaTest.supportsMpeg2()) {
			put("mpeg1video", MimeTypes.VIDEO_MPEG)
			put("mpeg2video", MimeTypes.VIDEO_MPEG2)
		}
		if (!mediaTest.supportsMpeg4Simple() && !mediaTest.supportsMpeg4Asp()) put("mpeg4", MimeTypes.VIDEO_MP4V)
		if (!mediaTest.supportsVc1()) put("vc1", MimeTypes.VIDEO_VC1)
	}.filterValues(FfmpegLibrary::supportsFormat).keys
	if (missingCodecs.isEmpty()) return this

	val softwareProfiles = buildDeviceProfile {
		for (videoCodec in missingCodecs) codecProfile {
			type = CodecType.VIDEO
			codec = videoCodec
			conditions {
				// A decoder being present does not establish real-time 4K/HDR capability.
				ProfileConditionValue.WIDTH lowerThanOrEquals maxResolution.capWidth(1920)
				ProfileConditionValue.HEIGHT lowerThanOrEquals maxResolution.capHeight(1080)
				ProfileConditionValue.VIDEO_FRAMERATE lowerThanOrEquals 30
				ProfileConditionValue.VIDEO_BIT_DEPTH lowerThanOrEquals 8
				ProfileConditionValue.VIDEO_RANGE_TYPE equals VideoRangeType.SDR.serialName
				when (videoCodec) {
					"h264" -> {
						ProfileConditionValue.VIDEO_PROFILE inCollection listOf("high", "main", "baseline", "constrained baseline")
						ProfileConditionValue.VIDEO_LEVEL lowerThanOrEquals minOf(userAVCLevel ?: 41, 41)
					}
					"hevc" -> {
						ProfileConditionValue.VIDEO_PROFILE equals "main"
						ProfileConditionValue.VIDEO_LEVEL lowerThanOrEquals minOf(userHEVCLevel ?: 120, 120)
					}
					"av1" -> ProfileConditionValue.VIDEO_PROFILE equals "main"
					"vp9" -> ProfileConditionValue.VIDEO_PROFILE equals "profile 0"
					"mpeg4" -> ProfileConditionValue.VIDEO_PROFILE inCollection listOf("Simple Profile", "Advanced Simple Profile")
				}
			}
		}
		for (videoCodec in missingCodecs) {
			addUnsupportedVideoRanges(videoCodec, VideoRangeType.entries.filterNot { it == VideoRangeType.SDR }.toSet())
		}
	}.codecProfiles
	return copy(
		codecProfiles = codecProfiles.filterNot { it.type == CodecType.VIDEO && it.codec in missingCodecs } + softwareProfiles,
		transcodingProfiles = transcodingProfiles.map { profile ->
			if (profile.type != DlnaProfileType.VIDEO || profile.container != "mp4") return@map profile
			val remuxCodecs = listOf("hevc", "av1", "vp9").filter { it in missingCodecs }
			profile.copy(videoCodec = (profile.videoCodec.orEmpty().split(',') + remuxCodecs).filter(String::isNotBlank).distinct().joinToString(","))
		},
	)
}
