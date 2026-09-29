package org.jellyfin.playback.mpv

import `is`.xyz.mpv.MPVNode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.model.VideoAspectRatio
import org.jellyfin.playback.core.model.VideoGeometry
import org.jellyfin.playback.core.model.VideoOutputTransform

class MPVVideoGeometryTests : FunSpec({
	test("decoder parameters prefer cropped visible dimensions") {
		mpvVideoGeometry(node(
			"w" to 736L,
			"h" to 576L,
			"crop-w" to 720L,
			"crop-h" to 576L,
			"aspect" to (16.0 / 9.0),
		)) shouldBe VideoGeometry(720, 576, 16f / 9f)
	}

	test("display dimensions provide video aspect fallback") {
		mpvVideoGeometry(node(
			"w" to 1920L,
			"h" to 800L,
			"dw" to 1920L,
			"dh" to 800L,
		)) shouldBe VideoGeometry(1920, 800, 2.4f)
	}

	test("quarter turn swaps dimensions and inverts video aspect") {
		mpvVideoGeometry(node(
			"w" to 1920L,
			"h" to 1080L,
			"aspect" to (16.0 / 9.0),
			"rotate" to 90L,
		)) shouldBe VideoGeometry(1080, 1920, 9f / 16f)

		mpvVideoGeometry(node(
			"w" to 1920L,
			"h" to 1080L,
			"aspect" to (16.0 / 9.0),
			"rotate" to 270L,
		)) shouldBe VideoGeometry(1080, 1920, 9f / 16f)
	}

	test("invalid display information retains frame dimensions only") {
		mpvVideoGeometry(node(
			"w" to 720L,
			"h" to 576L,
			"aspect" to Double.NaN,
			"dw" to 0L,
			"dh" to 576L,
		)) shouldBe VideoGeometry(720, 576, null)
	}

	test("output aspect writes exact dimensions and deduplicates requests") {
		val writes = mutableListOf<String>()
		val output = MPVVideoOutput(writes::add)
		val transform = VideoOutputTransform(VideoAspectRatio(1920, 1080))

		output.apply(transform)
		output.apply(transform)

		writes shouldBe listOf("1920:1080")
	}

	test("neutral output clears MPV aspect with no") {
		val writes = mutableListOf<String>()
		val output = MPVVideoOutput(writes::add)
		output.apply(VideoOutputTransform(VideoAspectRatio(1920, 800)))

		output.apply(VideoOutputTransform.NONE)

		writes shouldBe listOf("1920:800", "no")
	}
})

private fun node(vararg values: Pair<String, Any>) = MPVNode.MapNode(
	values.associate { (key, value) ->
		key to when (value) {
			is Long -> MPVNode.IntNode(value)
			is Double -> MPVNode.DoubleNode(value)
			else -> error("Unsupported MPV test node value $value")
		}
	}
)
