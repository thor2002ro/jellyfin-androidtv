package org.jellyfin.playback.media3.exoplayer

import io.github.peerless2012.ass.media.type.AssRenderType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LibassPlaybackTests : FunSpec({
	test("libass maximum FPS defaults to 35") {
		ExoPlayerOptions().libassMaxFps shouldBe 35f
	}

	test("libass disables next item preloading") {
		canPreloadNextItem(libassEnabled = true) shouldBe false
		canPreloadNextItem(libassEnabled = false) shouldBe true
	}

	test("libass performance stats only measure libass renderers") {
		canMeasureLibassPerformance(libassEnabled = false, renderType = AssRenderType.OVERLAY_OPEN_GL) shouldBe false
		canMeasureLibassPerformance(libassEnabled = true, renderType = AssRenderType.CUES) shouldBe false
		canMeasureLibassPerformance(libassEnabled = true, renderType = AssRenderType.OVERLAY_OPEN_GL) shouldBe true
		canMeasureLibassPerformance(libassEnabled = true, renderType = AssRenderType.OVERLAY_CANVAS) shouldBe true
	}
})
