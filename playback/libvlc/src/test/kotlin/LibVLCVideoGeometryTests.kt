package org.jellyfin.playback.libvlc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.model.VideoAspectRatio
import org.jellyfin.playback.core.model.VideoGeometry
import org.jellyfin.playback.core.model.VideoOutputTransform

class LibVLCVideoGeometryTests : FunSpec({
	test("VLC 3 layout reports visible dimensions and sample aspect") {
		libVLCVideoGeometry(720, 576, 720, 576, 64, 45) shouldBe VideoGeometry(720, 576, 16f / 9f)
		libVLCVideoGeometry(1920, 1088, 1920, 1080, 1, 1) shouldBe VideoGeometry(1920, 1080, 16f / 9f)
		libVLCVideoGeometry(720, 576, 720, 576, 0, 0) shouldBe VideoGeometry(720, 576, null)
		libVLCVideoGeometry(0, 0, 0, 0, 0, 0) shouldBe VideoGeometry.EMPTY
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
