package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.VideoSize
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.model.VideoGeometry

class Media3VideoGeometryTests : FunSpec({
	test("anamorphic pixel ratio becomes display aspect") {
		val geometry = VideoSize(720, 576, 64f / 45f).toVideoGeometry()

		geometry.frameWidth shouldBe 720
		geometry.frameHeight shouldBe 576
		geometry.displayAspectRatio!!.shouldBeExactly(16f / 9f)
	}

	test("invalid pixel ratios use the shared frame fallback") {
		listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { pixelRatio ->
			VideoSize(720, 576, pixelRatio).toVideoGeometry() shouldBe
				VideoGeometry(720, 576, null)
		}
	}

	test("unknown Media3 video size becomes empty geometry") {
		VideoSize.UNKNOWN.toVideoGeometry() shouldBe VideoGeometry.EMPTY
	}
})
