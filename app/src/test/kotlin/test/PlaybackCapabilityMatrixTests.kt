package test

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.test.PlaybackAudioDescriptor
import org.jellyfin.androidtv.test.PlaybackCapabilityCause
import org.jellyfin.androidtv.test.PlaybackCapabilityMatrix
import org.jellyfin.androidtv.test.PlaybackMediaDescriptor

class PlaybackCapabilityMatrixTests : FunSpec({
	val complete = PlaybackMediaDescriptor(
		id = "complete",
		name = "complete",
		container = "mkv",
		width = 3840,
		height = 2160,
		videoCodec = "hevc",
		videoProfile = "main 10",
		videoLevel = 153,
		bitDepth = 10,
		videoRange = "HDR10",
		doviProfile = null,
		subtitleCodecs = setOf("ass"),
		audioCodecs = setOf("truehd"),
		videoFrameRate = 59.94f,
		videoRefFrames = 4,
		videoInterlaced = false,
		videoAnamorphic = false,
		videoBitrate = 40_000_000,
		containerBitrate = 45_000_000,
		videoStreamCount = 1,
		audioStreamCount = 2,
		audioStreams = listOf(PlaybackAudioDescriptor("truehd", "dolby truehd", 8, 192_000, 24, 6_000_000)),
	)

	test("matrix creates every reason supported by Jellyfin profile conditions") {
		val causes = PlaybackCapabilityMatrix.plan(listOf(complete)).map { it.cause }

		causes shouldContainAll listOf(
			PlaybackCapabilityCause.VIDEO_LEVEL,
			PlaybackCapabilityCause.VIDEO_RESOLUTION,
			PlaybackCapabilityCause.VIDEO_BIT_DEPTH,
			PlaybackCapabilityCause.VIDEO_FRAMERATE,
			PlaybackCapabilityCause.REF_FRAMES,
			PlaybackCapabilityCause.INTERLACED_VIDEO,
			PlaybackCapabilityCause.ANAMORPHIC_VIDEO,
			PlaybackCapabilityCause.VIDEO_RANGE,
			PlaybackCapabilityCause.VIDEO_BITRATE,
			PlaybackCapabilityCause.AUDIO_CHANNELS,
			PlaybackCapabilityCause.AUDIO_PROFILE,
			PlaybackCapabilityCause.AUDIO_SAMPLE_RATE,
			PlaybackCapabilityCause.AUDIO_BIT_DEPTH,
			PlaybackCapabilityCause.AUDIO_BITRATE,
			PlaybackCapabilityCause.STREAM_COUNT,
			PlaybackCapabilityCause.CONTAINER_BITRATE,
		)
	}

	test("causes retain the exact Jellyfin reason") {
		PlaybackCapabilityCause.VIDEO_LEVEL.reason shouldBe "VideoLevelNotSupported"
		PlaybackCapabilityCause.VIDEO_RANGE.reason shouldBe "VideoRangeTypeNotSupported"
		PlaybackCapabilityCause.STREAM_COUNT.reason shouldBe "StreamCountExceedsLimit"
	}

	test("unexpressible audio fields are reported explicitly") {
		PlaybackCapabilityMatrix.unsupportedReasons shouldBe setOf(
			"InterlacedVideoNotSupported",
			"AudioSampleRateNotSupported",
			"AudioBitDepthNotSupported",
		)
	}
})
