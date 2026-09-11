package org.jellyfin.androidtv.util.profile.codec

import android.media.MediaCodecInfo.CodecProfileLevel
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.jellyfin.androidtv.util.AndroidVersion

@OptIn(UnstableApi::class)
class Vp9CodecCapabilities(
	private val query: MediaCodecQuery,
) {
	private val supportsProfile0 by lazy {
		query.hasDecoder(MimeTypes.VIDEO_VP9, CodecProfileLevel.VP9Profile0, CodecProfileLevel.VP9Level1)
	}
	private val supportsProfile2 by lazy {
		query.hasDecoder(MimeTypes.VIDEO_VP9, CodecProfileLevel.VP9Profile2, CodecProfileLevel.VP9Level1)
	}
	private val supportsProfile2Hdr by lazy {
		query.hasDecoder(MimeTypes.VIDEO_VP9, CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Level1)
	}
	private val supportsProfile2Hdr10Plus by lazy {
		AndroidVersion.isAtLeastQ && query.hasDecoder(
			MimeTypes.VIDEO_VP9,
			CodecProfileLevel.VP9Profile2HDR10Plus,
			CodecProfileLevel.VP9Level1,
		)
	}

	fun supportsVp9(): Boolean = supportsProfile0 || supportsVp9Main10()

	fun supportsVp9Main8(): Boolean = supportsProfile0

	fun supportsVp9Main10(): Boolean = supportsProfile2 || supportsProfile2Hdr || supportsProfile2Hdr10Plus

	fun supportsVp9Hdr(): Boolean = supportsProfile2Hdr

	fun supportsVp9Hdr10Plus(): Boolean = supportsProfile2Hdr10Plus
}
