package org.jellyfin.androidtv.test

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PlaybackTestResultTests : FunSpec({
	test("runner arguments default to the complete matrix") {
		PlaybackTestArguments.from(emptyMap()) shouldBe PlaybackTestArguments(
			suites = setOf("resume", "server", "transcode", "backend", "player-flow", "soak", "recovery", "hdmi-audio", "updater"),
			backend = null,
			scenario = null,
			testUser = "androidtv-playback-test",
			testFolder = "Test Videos",
			soakIterations = 3,
		)
	}

	test("runner arguments select the dedicated transcoding suite") {
		PlaybackTestArguments.from(mapOf("suite" to "transcode")) shouldBe PlaybackTestArguments(
			suites = setOf("transcode"),
			backend = null,
			scenario = null,
			testUser = "androidtv-playback-test",
			testFolder = "Test Videos",
			soakIterations = 3,
		)
	}

	test("runner arguments select a suite and trim optional filters") {
		PlaybackTestArguments.from(
			mapOf(
				"suite" to " server ",
				"backend" to " MPV ",
				"scenario" to " 4k-dv7-fel ",
				"testUser" to " tv-tests ",
				"testFolder" to " Playback Matrix ",
			)
		) shouldBe PlaybackTestArguments(setOf("server"), "MPV", "4k-dv7-fel", "tv-tests", "Playback Matrix", 3)
	}

	test("result line has a stable filterable shape") {
		PlaybackTestResult(
			status = PlaybackTestStatus.PASS,
			suite = "backend",
			backend = "MPV",
			scenario = "4k-dv7-fel",
			detail = "playing positionMs=15266",
		).line() shouldBe "PASS backend/MPV/4k-dv7-fel playing positionMs=15266"
	}

	test("summary counts warnings and skips without treating them as failures") {
		val summary = PlaybackTestSummary(
			listOf(
				PlaybackTestResult(PlaybackTestStatus.PASS, "server", scenario = "1080p-sdr"),
				PlaybackTestResult(PlaybackTestStatus.WARN, "server", scenario = "4k-dv5"),
				PlaybackTestResult(PlaybackTestStatus.SKIP, "backend", backend = "VLC", scenario = "libass"),
			)
		)

		summary.counts shouldBe mapOf("pass" to 1, "fail" to 0, "warn" to 1, "skip" to 1)
		summary.failed shouldBe false
	}

	test("report redacts bearer tokens and api keys") {
		val report = PlaybackTestSummary(
			listOf(
				PlaybackTestResult(
					PlaybackTestStatus.FAIL,
					"server",
					detail = "Authorization: Bearer secret-token url=?api_key=secret-key&x=1",
				)
			)
		).toJson()

		report shouldContain "<redacted>"
		report shouldNotContain "secret-token"
		report shouldNotContain "secret-key"
	}
})
