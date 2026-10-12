package org.jellyfin.androidtv.test

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class PlaybackMediaCatalogTests : FunSpec({
	fun media(
		id: String,
		width: Int = 1920,
		height: Int = 1080,
		videoCodec: String = "h264",
		videoProfile: String? = "high",
		videoRange: String = "SDR",
		dvProfile: Int? = null,
		subtitles: Set<String> = emptySet(),
		audio: Set<String> = setOf("aac"),
		container: String = "mkv",
	) = PlaybackMediaDescriptor(id, id, container, width, height, videoCodec, videoProfile, null, 8, videoRange, dvProfile, subtitles, audio)

	test("catalog selects the requested core video fixtures") {
		val selected = PlaybackMediaCatalog.select(
			listOf(
				media("sdr"),
				media("ass", subtitles = setOf("ass")),
				media("hdr10", 3840, 2160, "hevc", "main 10", "HDR10"),
				media("dv5", 3840, 2160, "hevc", "main 10", "DOVI", 5),
				media("dv7", 3840, 2160, "hevc", "main 10", "DOVI_WITH_EL", 7),
				media("dv8", 3840, 2160, "hevc", "main 10", "DOVI_WITH_HDR10", 8),
			)
		)

		selected.fixtures.getValue("1080p-sdr-avc").id shouldBe "sdr"
		selected.fixtures.getValue("1080p-sdr-ass").id shouldBe "ass"
		selected.fixtures.getValue("4k-hdr10-hevc").id shouldBe "hdr10"
		selected.fixtures.getValue("4k-dv5").id shouldBe "dv5"
		selected.fixtures.getValue("4k-dv7").id shouldBe "dv7"
		selected.fixtures.getValue("4k-dv8").id shouldBe "dv8"
	}

	test("missing fixtures are warnings") {
		val selected = PlaybackMediaCatalog.select(listOf(media("sdr")))

		selected.warnings shouldContain "MISSING_FIXTURE 1080p-sdr-ass"
		selected.warnings shouldContain "MISSING_FIXTURE 4k-hdr10-hevc"
		selected.fixtures shouldContainKey "1080p-sdr-avc"
	}

	test("SDR control fixture prefers a conventional direct-play source") {
		val selected = PlaybackMediaCatalog.select(
			listOf(
				media("mkv-opus", audio = setOf("opus"), container = "mkv"),
				media("mp4-aac", videoProfile = "main", audio = setOf("aac"), container = "mp4"),
			)
		)

		selected.fixtures.getValue("1080p-sdr-avc").id shouldBe "mp4-aac"
	}

	test("catalog reports discovered codec audio and container combinations") {
		val selected = PlaybackMediaCatalog.select(
			listOf(
				media("one", videoCodec = "vp9", videoProfile = "profile 2", audio = setOf("opus"), container = "webm"),
				media("two", videoCodec = "hevc", videoProfile = "main 10", audio = setOf("eac3", "truehd"), container = "mkv"),
			)
		)

		selected.coverage.video shouldBe setOf("hevc/main 10", "vp9/profile 2")
		selected.coverage.audio shouldBe setOf("eac3", "opus", "truehd")
		selected.coverage.containers shouldBe setOf("mkv", "webm")
	}

	test("catalog json maps every discovered media source") {
		val json = playbackMediaCatalogJson(listOf(media("one"), media("two", videoCodec = "hevc")))

		json shouldContain "\"id\": \"one\""
		json shouldContain "\"id\": \"two\""
		json shouldContain "\"videoCodec\": \"hevc\""
	}
})
