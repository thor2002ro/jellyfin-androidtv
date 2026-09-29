package org.jellyfin.playback.jellyfin.mediastream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.playback.jellyfin.JellyfinDeviceProfileRequest
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

class DoviStreamCopyTests : FunSpec({
	val base = "/Videos/item/master.m3u8?VideoCodec=hevc,h264&TranscodeReasons=AudioCodecNotSupported"
	test("backends without protected variant selection do not opt into the workaround") {
		JellyfinDeviceProfileRequest(mockk(), 0).protectsDoviHlsVideoCopy shouldBe false
	}
	fun source(url: String? = base, hasDovi: Boolean = true): MediaSourceInfo = mockk(relaxed = true) {
		every { transcodingUrl } returns url
		every { mediaStreams } returns listOf(mockk<MediaStream>(relaxed = true) {
			every { type } returns MediaStreamType.VIDEO
			every { codec } returns "hevc"
			every { dvProfile } returns if (hasDovi) 7 else null
			every { rpuPresentFlag } returns if (hasDovi) 1 else null
		})
	}
	test("audio-only Server 12 transcode can preserve Dolby video") {
		source().isDoviVideoCopyCandidate() shouldBe true
	}
	test("container remux can preserve Dolby video") {
		source(base.replace("AudioCodecNotSupported", "ContainerNotSupported")).isDoviVideoCopyCandidate() shouldBe true
	}
	test("video conversion and unknown reasons must not preserve a Dolby plan") {
		listOf("VideoRangeTypeNotSupported", "VideoCodecNotSupported", "ContainerBitrateExceedsLimit", "SubtitleCodecNotSupported", "Unknown", "")
			.forEach { reason -> source(base.replace("AudioCodecNotSupported", reason)).isDoviVideoCopyCandidate() shouldBe false }
	}
	test("copy prohibition burned subtitles and different output codec reject local transform") {
		listOf("&allowVideoStreamCopy=false", "&SubtitleMethod=Encode", "&VideoCodec=h264")
			.forEach { suffix -> source(base + suffix).isDoviVideoCopyCandidate() shouldBe false }
		source(base.replace("hevc,h264", "h264")).isDoviVideoCopyCandidate() shouldBe false
	}
	test("range rewriting and non Dolby sources are not assumed to preserve RPU") {
		source(base + "&hevc-rangetype=HDR10").isDoviVideoCopyCandidate() shouldBe false
		source(hasDovi = false).isDoviVideoCopyCandidate() shouldBe false
	}
	test("missing malformed and non HLS URLs fail closed") {
		source(null).isDoviVideoCopyCandidate() shouldBe false
		listOf("bad url", base.replace("master.m3u8", "stream.mkv"), base.substringBefore("&TranscodeReasons"))
			.forEach { source(it).isDoviVideoCopyCandidate() shouldBe false }
	}
})
