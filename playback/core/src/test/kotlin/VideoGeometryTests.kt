package org.jellyfin.playback.core.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeExactly

class VideoGeometryTests : FunSpec({
	test("display aspect overrides the coded frame ratio") {
		val geometry = VideoGeometry(
			frameWidth = 720,
			frameHeight = 576,
			displayAspectRatio = 16f / 9f,
		)

		geometry.videoAspectRatio.shouldBeExactly(16f / 9f)
	}

	test("invalid display aspects fall back to the frame ratio") {
		listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { displayAspect ->
			val geometry = VideoGeometry(1920, 800, displayAspect)

			geometry.videoAspectRatio.shouldBeExactly(2.4f)
		}
	}

	test("invalid frame dimensions have no effective aspect") {
		listOf(
			VideoGeometry.EMPTY,
			VideoGeometry(1920, 0, null),
			VideoGeometry(0, 1080, null),
			VideoGeometry(-1, 1080, null),
		).forEach { geometry ->
			geometry.videoAspectRatio.shouldBeExactly(0f)
		}
	}

	test("output aspect rejects non-positive dimensions") {
		shouldThrow<IllegalArgumentException> { VideoAspectRatio(0, 1080) }
		shouldThrow<IllegalArgumentException> { VideoAspectRatio(1920, 0) }
		shouldThrow<IllegalArgumentException> { VideoAspectRatio(-1, 1080) }
	}
})
