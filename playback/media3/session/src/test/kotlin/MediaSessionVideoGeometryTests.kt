package org.jellyfin.playback.media3.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.model.VideoGeometry

class MediaSessionVideoGeometryTests : FunSpec({
	test("anamorphic video aspect is preserved as a Media3 pixel ratio") {
		val videoSize = VideoGeometry(720, 576, 16f / 9f).toMedia3VideoSize()

		videoSize.width shouldBe 720
		videoSize.height shouldBe 576
		videoSize.pixelWidthHeightRatio shouldBe ((64f / 45f) plusOrMinus 0.000001f)
	}

	test("unknown geometry maps to Media3 unknown video size") {
		VideoGeometry.EMPTY.toMedia3VideoSize() shouldBe androidx.media3.common.VideoSize.UNKNOWN
	}
})
