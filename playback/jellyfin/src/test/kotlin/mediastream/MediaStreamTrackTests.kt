package org.jellyfin.playback.jellyfin.mediastream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType

class MediaStreamTrackTests : FunSpec({
	fun videoStream(realFrameRate: Float?, referenceFrameRate: Float?) = MediaStream(
		type = MediaStreamType.VIDEO,
		codec = "vp9",
		videoRangeType = VideoRangeType.SDR,
		realFrameRate = realFrameRate,
		referenceFrameRate = referenceFrameRate,
		isInterlaced = false,
		isDefault = true,
		isForced = false,
		isHearingImpaired = false,
		isOriginal = true,
		index = 0,
		isExternal = false,
		isTextSubtitleStream = false,
		supportsExternalStream = false,
	)

	test("video tracks use Jellyfin's preferred reference frame rate") {
		val stream = videoStream(realFrameRate = null, referenceFrameRate = 59.94f)

		(stream.getMediaStreamTrack() as MediaStreamVideoTrack).realFrameRate shouldBe 59.94f
	}

	test("video tracks retain the real frame rate when no reference is available") {
		val stream = videoStream(realFrameRate = 50f, referenceFrameRate = null)

		(stream.getMediaStreamTrack() as MediaStreamVideoTrack).realFrameRate shouldBe 50f
	}
})
