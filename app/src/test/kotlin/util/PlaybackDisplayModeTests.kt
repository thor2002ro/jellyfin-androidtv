package org.jellyfin.androidtv.util

import android.view.Display
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class PlaybackDisplayModeTests : FunSpec({
	val current = mode(1, 3840, 2160, 60f)
	val hd60 = mode(2, 1920, 1080, 60f)
	val hd24 = mode(3, 1920, 1080, 24f)
	val uhd24 = mode(4, 3840, 2160, 24f)
	val modes = arrayOf(current, hd60, hd24, uhd24)
	test("25 fps interlaced playback selects 50 Hz instead of the 25 Hz mode") {
		val hd25 = mode(6, 1920, 1080, 25f)
		val hd50 = mode(7, 1920, 1080, 50f)
		selectPlaybackDisplayMode(
			arrayOf(hd25, hd50), hd25, 1920, 1080,
			PlaybackDisplayMode.frameRateForDisplay(25f, true), true, false,
		)?.modeId shouldBe 7
	}
	test("fractional interlaced playback selects 59.94 Hz instead of 29.97 Hz") {
		val hd2997 = mode(6, 1920, 1080, 29.97f)
		val hd5994 = mode(7, 1920, 1080, 59.94f)
		selectPlaybackDisplayMode(
			arrayOf(hd2997, hd5994), hd2997, 1920, 1080,
			PlaybackDisplayMode.frameRateForDisplay(29.97f, true), true, false,
		)?.modeId shouldBe 7
	}
	test("progressive and unknown scan types preserve the advertised rate") {
		PlaybackDisplayMode.frameRateForDisplay(25f, false) shouldBe 25f
		PlaybackDisplayMode.frameRateForDisplay(25f, null) shouldBe 25f
		PlaybackDisplayMode.frameRateForDisplay(null, true) shouldBe null
	}
	test("film playback does not upscale to 59.94 when the platform hides film modes") {
		val hd5994 = mode(2, 1920, 1080, 59.94f)
		val uhd5994 = mode(3, 3840, 2160, 59.94f)
		selectPlaybackDisplayMode(arrayOf(hd5994, uhd5994), hd5994, 3832, 1600, 23.976025f, true, true) shouldBe null
	}
	test("film playback selects 4K 23.976 when the platform exposes it") {
		val hd5994 = mode(2, 1920, 1080, 59.94f)
		val uhd5994 = mode(3, 3840, 2160, 59.94f)
		val uhd23976 = mode(11, 3840, 2160, 23.976f)
		selectPlaybackDisplayMode(arrayOf(hd5994, uhd5994, uhd23976), hd5994, 3832, 1600, 23.976025f, true, true)?.modeId shouldBe 11
	}
	test("whole-number multiples support 24 fps on a 120 Hz display") {
		val uhd120 = mode(5, 3840, 2160, 120f)
		selectPlaybackDisplayMode(arrayOf(current, uhd120), current, 3840, 2160, 24f, true, false)?.modeId shouldBe 5
	}
	test("resolution only preserves refresh rate even without video frame rate") {
		selectPlaybackDisplayMode(modes, current, 1920, 800, null, false, true)?.modeId shouldBe 2
	}
	test("resolution only skips an unsupported combination instead of changing refresh rate") {
		selectPlaybackDisplayMode(arrayOf(current, hd24), current, 1920, 1080, 24f, false, true)?.modeId shouldBe 1
	}
	test("refresh only preserves output resolution") {
		selectPlaybackDisplayMode(modes, current, 1920, 1080, 24f, true, false)?.modeId shouldBe 4
	}
	test("combined switching matches both dimensions") {
		selectPlaybackDisplayMode(modes, current, 1920, 1080, 24f, true, true)?.modeId shouldBe 3
	}
	test("disabled switching does not request a mode") {
		selectPlaybackDisplayMode(modes, current, 1920, 1080, 24f, false, false) shouldBe null
	}
	test("fractional rates remain distinct from integer rates") {
		val hd5994 = mode(5, 1920, 1080, 59.94f)
		selectPlaybackDisplayMode(arrayOf(current, hd5994), current, 1920, 1080, 24f, false, true)?.modeId shouldBe 1
	}
})

private fun mode(id: Int, width: Int, height: Int, rate: Float): Display.Mode = mockk {
	every { modeId } returns id
	every { physicalWidth } returns width
	every { physicalHeight } returns height
	every { refreshRate } returns rate
}
