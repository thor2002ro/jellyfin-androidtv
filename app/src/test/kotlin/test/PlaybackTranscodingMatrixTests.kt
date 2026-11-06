package test

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.test.PlaybackMediaDescriptor
import org.jellyfin.androidtv.test.PlaybackProfileVariant
import org.jellyfin.androidtv.test.PlaybackTranscodingMatrix
import org.jellyfin.androidtv.test.transcodeReasons

class PlaybackTranscodingMatrixTests : FunSpec({
	fun media(
		id: String,
		width: Int,
		height: Int,
		videoCodec: String,
		profile: String,
		level: Int,
		bitDepth: Int,
		range: String,
		dvProfile: Int? = null,
		audio: Set<String> = emptySet(),
		subtitles: Set<String> = emptySet(),
	) = PlaybackMediaDescriptor(
		id = id,
		name = id,
		container = "mkv",
		width = width,
		height = height,
		videoCodec = videoCodec,
		videoProfile = profile,
		videoLevel = level,
		bitDepth = bitDepth,
		videoRange = range,
		doviProfile = dvProfile,
		subtitleCodecs = subtitles,
		audioCodecs = audio,
	)

	test("matrix forces one video conversion for every distinct decoding signature") {
		val cases = PlaybackTranscodingMatrix.plan(
			listOf(
				media("avc-a", 1920, 1080, "h264", "high", 41, 8, "SDR"),
				media("avc-duplicate", 1920, 1080, "h264", "high", 41, 8, "SDR"),
				media("hdr10", 3840, 2160, "hevc", "main 10", 153, 10, "HDR10"),
				media("dv7", 3840, 2160, "hevc", "main 10", 153, 10, "DOVI_WITH_EL", 7),
			)
		)

		val video = cases.filter { it.variant == PlaybackProfileVariant.VIDEO_TRANSCODE }
		video.map { it.descriptorId } shouldContainExactlyInAnyOrder listOf("avc-a", "hdr10", "dv7")
		video.map { it.id } shouldContainExactlyInAnyOrder listOf(
			"video-1080p-h264-high-l41-8bit-sdr",
			"video-4k-hevc-main-10-l153-10bit-hdr10",
			"video-4k-hevc-main-10-l153-10bit-dovi-with-el-dv7",
		)
	}

	test("matrix rejects every distinct codec profile without disabling the codec") {
		val cases = PlaybackTranscodingMatrix.plan(
			listOf(
				media("avc-high", 1920, 1080, "h264", "high", 41, 8, "SDR"),
				media("avc-high-duplicate", 1280, 720, "h264", "HIGH", 40, 8, "SDR"),
				media("avc-high10", 1920, 1080, "h264", "high 10", 51, 10, "SDR"),
				media("hevc-main10", 3840, 2160, "hevc", "main 10", 153, 10, "HDR10"),
			)
		)

		val profiles = cases.filter { it.variant == PlaybackProfileVariant.VIDEO_PROFILE_TRANSCODE }
		profiles.map { it.id to it.descriptorId } shouldContainExactlyInAnyOrder listOf(
			"profile-h264-high" to "avc-high",
			"profile-h264-high-10" to "avc-high10",
			"profile-hevc-main-10" to "hevc-main10",
		)
	}

	test("matrix selects a real source track for every discovered audio and subtitle codec") {
		val cases = PlaybackTranscodingMatrix.plan(
			listOf(
				media("first", 1920, 1080, "h264", "high", 41, 8, "SDR", audio = setOf("AAC", "DTS"), subtitles = setOf("ASS")),
				media("second", 3840, 2160, "hevc", "main 10", 153, 10, "HDR10", audio = setOf("TrueHD"), subtitles = setOf("PGSSUB")),
			)
		)

		cases.filter { it.variant == PlaybackProfileVariant.AUDIO_TRANSCODE }
			.map { it.id to it.selectedAudioCodec } shouldContainExactlyInAnyOrder listOf(
				"audio-aac" to "aac",
				"audio-dts" to "dts",
				"audio-truehd" to "truehd",
			)
		cases.filter { it.variant == PlaybackProfileVariant.SUBTITLE_TRANSCODE }
			.map { it.id to it.selectedSubtitleCodec } shouldContainExactlyInAnyOrder listOf(
				"subtitle-ass" to "ass",
				"subtitle-pgssub" to "pgssub",
			)
	}

	test("audio conversion prefers a conventional SDR AVC source over an unsupported video") {
		val cases = PlaybackTranscodingMatrix.plan(
			listOf(
				media("a-vp9", 1920, 1080, "vp9", "profile 0", -99, 8, "SDR", audio = setOf("aac")),
				media("z-avc", 1920, 1080, "h264", "high", 41, 8, "SDR", audio = setOf("aac")),
			)
		)

		cases.single { it.id == "audio-aac" }.descriptorId shouldBe "z-avc"
	}

	test("transcoding reasons are decoded into independently assertable values") {
		transcodeReasons("https://server.invalid/master.m3u8?TranscodeReasons=VideoCodecNotSupported%2CAudioCodecNotSupported") shouldBe
			setOf("VideoCodecNotSupported", "AudioCodecNotSupported")
	}

	test("matrix ignores audio-only entries without a video signature") {
		val cases = PlaybackTranscodingMatrix.plan(
			listOf(media("broken", 0, 0, "", "", 0, 0, "SDR", audio = setOf("aac")))
		)

		cases.count { it.variant == PlaybackProfileVariant.VIDEO_TRANSCODE } shouldBe 0
		cases.count { it.variant == PlaybackProfileVariant.AUDIO_TRANSCODE } shouldBe 1
	}

	test("live matrix selects video audio and subtitle conversion fixtures") {
		val selection = org.jellyfin.androidtv.test.PlaybackCatalogSelection(
			fixtures = mapOf(
				"1080p-sdr-avc" to media(
					"avc",
					1920,
					1080,
					"h264",
					"high",
					41,
					8,
					"SDR",
					audio = setOf("aac"),
				),
				"1080p-sdr-ass" to media(
					"ass",
					1920,
					1080,
					"h264",
					"high",
					41,
					8,
					"SDR",
					subtitles = setOf("ass"),
				),
			),
			warnings = emptyList(),
			coverage = org.jellyfin.androidtv.test.PlaybackCoverage(emptySet(), emptySet(), emptySet()),
		)

		PlaybackTranscodingMatrix.planLivePlayback(selection) shouldBe listOf(
			org.jellyfin.androidtv.test.PlaybackTranscodingCase(
				"video-playback",
				"avc",
				PlaybackProfileVariant.VIDEO_TRANSCODE,
			),
			org.jellyfin.androidtv.test.PlaybackTranscodingCase(
				"audio-playback-aac",
				"avc",
				PlaybackProfileVariant.AUDIO_TRANSCODE,
				selectedAudioCodec = "aac",
			),
			org.jellyfin.androidtv.test.PlaybackTranscodingCase(
				"subtitle-playback-ass",
				"ass",
				PlaybackProfileVariant.SUBTITLE_TRANSCODE,
				selectedSubtitleCodec = "ass",
			),
		)
	}
})
