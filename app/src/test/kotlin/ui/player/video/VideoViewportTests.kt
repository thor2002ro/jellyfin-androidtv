package org.jellyfin.androidtv.ui.player.video

import androidx.compose.ui.unit.IntSize
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.playback.core.model.VideoAspectRatio
import org.jellyfin.playback.core.model.VideoGeometry

class VideoViewportTests : FunSpec({
	val screen = IntSize(1920, 1080)

	test("normal uses the coded frame ratio like the old player") {
		calculateVideoViewport(
			container = screen,
			geometry = VideoGeometry(720, 576, 16f / 9f),
			zoomMode = ZoomMode.FIT,
		) shouldBe VideoViewport(1350, 1080, null)
	}

	test("auto uses display aspect for anamorphic PAL video") {
		calculateVideoViewport(
			container = screen,
			geometry = VideoGeometry(720, 576, 16f / 9f),
			zoomMode = ZoomMode.AUTO,
		) shouldBe VideoViewport(1920, 1080, null)
	}

	test("auto keeps its own label when the video aspect already fills the screen") {
		val viewport = calculateVideoViewport(screen, VideoGeometry(720, 576, 16f / 9f), ZoomMode.AUTO)

		videoZoomStatus(ZoomMode.AUTO, viewport, "Auto", "Stretch") shouldBe "Auto"
	}

	test("auto reports stretch when it overrides the source aspect") {
		val viewport = calculateVideoViewport(screen, VideoGeometry(1909, 1080, null), ZoomMode.AUTO)

		videoZoomStatus(ZoomMode.AUTO, viewport, "Auto", "Stretch") shouldBe "Auto → Stretch"
	}

	test("normal fit never applies the automatic tolerance") {
		calculateVideoViewport(screen, VideoGeometry(1909, 1080, null), ZoomMode.FIT) shouldBe
			VideoViewport(1909, 1080, null)
	}

	test("auto horizontal tolerance includes eleven pixels but excludes twelve") {
		calculateVideoViewport(screen, VideoGeometry(1909, 1080, null), ZoomMode.AUTO) shouldBe
			VideoViewport(1920, 1080, VideoAspectRatio(1920, 1080))
		calculateVideoViewport(screen, VideoGeometry(1908, 1080, null), ZoomMode.AUTO) shouldBe
			VideoViewport(1908, 1080, null)
	}

	test("auto vertical tolerance includes seven pixels but excludes eight") {
		calculateVideoViewport(screen, VideoGeometry(1920, 1073, null), ZoomMode.AUTO) shouldBe
			VideoViewport(1920, 1080, VideoAspectRatio(1920, 1080))
		calculateVideoViewport(screen, VideoGeometry(1920, 1072, null), ZoomMode.AUTO) shouldBe
			VideoViewport(1920, 1072, null)
	}

	test("auto fills an odd 853 by 480 frame that is one output pixel short") {
		calculateVideoViewport(screen, VideoGeometry(853, 480, null), ZoomMode.AUTO) shouldBe
			VideoViewport(1920, 1080, VideoAspectRatio(1920, 1080))
	}

	test("auto keeps cropped cinema video letterboxed") {
		calculateVideoViewport(screen, VideoGeometry(1920, 800, 2.4f), ZoomMode.AUTO) shouldBe
			VideoViewport(1920, 800, null)
	}

	test("auto crop fills the screen without distorting four by three video") {
		calculateVideoViewport(screen, VideoGeometry(720, 576, 4f / 3f), ZoomMode.AUTO_CROP) shouldBe
			VideoViewport(1920, 1440, null)
	}

	test("horizontal stretch widens four by three video without cropping") {
		calculateVideoViewport(screen, VideoGeometry(720, 576, 4f / 3f), ZoomMode.HORIZONTAL_STRETCH) shouldBe
			VideoViewport(1920, 1080, VideoAspectRatio(1920, 1080))
	}

	test("vertical stretch expands cinema video without cropping") {
		calculateVideoViewport(screen, VideoGeometry(1920, 800, 2.4f), ZoomMode.VERTICAL_STRETCH) shouldBe
			VideoViewport(1920, 1080, VideoAspectRatio(1920, 1080))
	}

	test("stretch fills the screen and overrides source aspect") {
		calculateVideoViewport(screen, VideoGeometry(720, 576, 4f / 3f), ZoomMode.STRETCH) shouldBe
			VideoViewport(1920, 1080, VideoAspectRatio(1920, 1080))
	}

	test("unknown geometry keeps a screen-sized surface attached for fast startup") {
		calculateVideoViewport(screen, VideoGeometry.EMPTY, ZoomMode.AUTO) shouldBe
			VideoViewport(1920, 1080, null)
	}
})
