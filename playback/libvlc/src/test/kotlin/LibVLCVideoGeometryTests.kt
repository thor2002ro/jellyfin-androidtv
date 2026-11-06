package org.jellyfin.playback.libvlc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.model.VideoAspectRatio
import org.jellyfin.playback.core.model.VideoGeometry
import org.jellyfin.playback.core.model.VideoOutputTransform

class LibVLCVideoGeometryTests : FunSpec({
	test("visible dimensions and sample aspect define video geometry") {
		libVLCVideoGeometry(
			width = 736,
			height = 576,
			visibleWidth = 720,
			visibleHeight = 576,
			sarNum = 64,
			sarDen = 45,
		) shouldBe VideoGeometry(720, 576, 16f / 9f)
	}

	test("invalid sample aspect uses frame fallback") {
		libVLCVideoGeometry(720, 576, 720, 576, 0, 1) shouldBe VideoGeometry(720, 576, null)
		libVLCVideoGeometry(720, 576, 720, 576, 1, 0) shouldBe VideoGeometry(720, 576, null)
	}

	test("output aspect writes exact dimensions and deduplicates identical requests") {
		val writes = mutableListOf<String?>()
		val output = LibVLCVideoOutput(writes::add)
		val transform = VideoOutputTransform(VideoAspectRatio(1920, 1080))

		output.apply(transform)
		output.apply(transform)

		writes shouldBe listOf("1920:1080")
	}

	test("neutral output clears LibVLC aspect with null") {
		val writes = mutableListOf<String?>()
		val output = LibVLCVideoOutput(writes::add)
		output.apply(VideoOutputTransform(VideoAspectRatio(1920, 800)))

		output.apply(VideoOutputTransform.NONE)

		writes shouldBe listOf("1920:800", null)
	}
})
