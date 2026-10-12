package org.jellyfin.androidtv.util.profile.codec

import android.media.MediaCodecInfo.CodecProfileLevel
import androidx.media3.common.MimeTypes
import org.jellyfin.androidtv.util.AndroidVersion

class Av1CodecCapabilities(
	private val query: MediaCodecQuery,
) {
	companion object {
		private const val MIME_AV1 = MimeTypes.VIDEO_AV1
		private const val MIME_DOLBY_VISION = MimeTypes.VIDEO_DOLBY_VISION

		// Fallback values from AOSP MediaCodecInfo.java for pre-Q/R devices
		// Some devices (e.g., Fire OS) support AV1 below the official API level
		internal const val AV1_PROFILE_MAIN10 = 0x2
		internal const val AV1_PROFILE_MAIN10_HDR10 = 0x1000
		internal const val AV1_PROFILE_MAIN10_HDR10_PLUS = 0x2000
		internal const val AV1_LEVEL2 = 0x1
		internal const val DV_PROFILE_DVAV1_10 = 0x400
	}

	private val profileMain10: Int
		get() = if (AndroidVersion.isAtLeastQ) CodecProfileLevel.AV1ProfileMain10 else AV1_PROFILE_MAIN10

	private val profileMain10HDR10: Int
		get() = if (AndroidVersion.isAtLeastQ) CodecProfileLevel.AV1ProfileMain10HDR10 else AV1_PROFILE_MAIN10_HDR10

	private val profileMain10HDR10Plus: Int
		get() = if (AndroidVersion.isAtLeastQ) CodecProfileLevel.AV1ProfileMain10HDR10Plus else AV1_PROFILE_MAIN10_HDR10_PLUS

	private val dolbyVisionProfile10: Int
		get() = if (AndroidVersion.isAtLeastR) CodecProfileLevel.DolbyVisionProfileDvav110 else DV_PROFILE_DVAV1_10

	private val level2: Int
		get() = if (AndroidVersion.isAtLeastQ) CodecProfileLevel.AV1Level2 else AV1_LEVEL2

	private val supportsMain10 by lazy { query.hasDecoder(MIME_AV1, profileMain10, level2) }
	private val supportsHdr10 by lazy { query.hasDecoder(MIME_AV1, profileMain10HDR10, level2) }
	private val supportsHdr10Plus by lazy { query.hasDecoder(MIME_AV1, profileMain10HDR10Plus, level2) }

	fun supportsAv1(): Boolean = query.hasCodecForMime(MIME_AV1)

	fun supportsAv1Main10(): Boolean =
		supportsMain10 || supportsHdr10 || supportsHdr10Plus

	fun supportsAv1DolbyVision(): Boolean =
		AndroidVersion.isAtLeastN &&
			query.hasDecoder(
				MIME_DOLBY_VISION,
				dolbyVisionProfile10,
				CodecProfileLevel.DolbyVisionLevelHd24,
			)

	fun supportsAv1HDR10(): Boolean = supportsHdr10

	fun supportsAv1HDR10Plus(): Boolean = supportsHdr10Plus
}
