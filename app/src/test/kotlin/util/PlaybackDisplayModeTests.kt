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
