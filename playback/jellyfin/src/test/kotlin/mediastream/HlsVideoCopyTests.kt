package org.jellyfin.playback.jellyfin.mediastream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.playback.jellyfin.JellyfinDeviceProfileRequest
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType

class HlsVideoCopyTests : FunSpec({
	val base = "/Videos/item/master.m3u8?VideoCodec=hevc,h264&TranscodeReasons=AudioCodecNotSupported"
	test("backends without protected variant selection do not opt into the workaround") {
		JellyfinDeviceProfileRequest(mockk(), 0).protectsHlsVideoCopy shouldBe false
	}
	fun source(
		url: String? = base,
		protocol: MediaStreamProtocol = MediaStreamProtocol.HLS,
		range: VideoRangeType = VideoRangeType.UNKNOWN,
	): MediaSourceInfo = mockk(relaxed = true) {
		every { transcodingUrl } returns url
		every { transcodingSubProtocol } returns protocol
		every { mediaStreams } returns listOf(mockk<MediaStream>(relaxed = true) {
			every { type } returns MediaStreamType.VIDEO
			every { codec } returns "hevc"
			every { videoRangeType } returns range
		})
	}
	test("audio-only Server 12 transcode can preserve video") {
		source().isHlsVideoCopyCandidate() shouldBe true
	}
	test("container remux can preserve video") {
		source(base.replace("AudioCodecNotSupported", "ContainerNotSupported")).isHlsVideoCopyCandidate() shouldBe true
	}
	test("video conversion and unknown reasons must not claim video copy") {
		listOf("VideoRangeTypeNotSupported", "VideoCodecNotSupported", "ContainerBitrateExceedsLimit", "SubtitleCodecNotSupported", "Unknown", "")
			.forEach { reason -> source(base.replace("AudioCodecNotSupported", reason)).isHlsVideoCopyCandidate() shouldBe false }
	}
	test("copy prohibition selected burned subtitles and different output codec reject video copy") {
		listOf("&allowVideoStreamCopy=false", "&SubtitleMethod=Encode&SubtitleStreamIndex=2", "&VideoCodec=h264")
			.forEach { suffix -> source(base + suffix).isHlsVideoCopyCandidate() shouldBe false }
		source(base.replace("hevc,h264", "h264")).isHlsVideoCopyCandidate() shouldBe false
	}
	test("an inert subtitle encode default does not force video conversion") {
		source(base + "&SubtitleMethod=Encode").isHlsVideoCopyCandidate() shouldBe true
	}
	test("range rewriting is rejected") {
		source(base + "&hevc-rangetype=HDR10").isHlsVideoCopyCandidate() shouldBe false
	}
	test("an accepted source range allows native Dolby Vision video copy") {
		source(base + "&hevc-rangetype=DOVI,DOVIWithHDR10", range = VideoRangeType.DOVI_WITH_HDR10)
			.isHlsVideoCopyCandidate() shouldBe true
		source(base + "&hevc-rangetype=DOVI,HDR10", range = VideoRangeType.DOVI_WITH_HDR10)
			.isHlsVideoCopyCandidate() shouldBe false
	}
	test("missing malformed and non HLS streams fail closed") {
		source(null).isHlsVideoCopyCandidate() shouldBe false
		listOf("bad url", base.substringBefore("&TranscodeReasons"))
			.forEach { source(it).isHlsVideoCopyCandidate() shouldBe false }
		source(protocol = MediaStreamProtocol.HTTP).isHlsVideoCopyCandidate() shouldBe false
	}
})
