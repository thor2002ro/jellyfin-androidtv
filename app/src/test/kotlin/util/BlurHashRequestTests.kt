package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BlurHashRequestTests : FunSpec({
	test("network images with a valid BlurHash get an aspect-correct decode request") {
		createBlurHashRequest(
			url = "https://example.test/poster",
			blurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
			isLowRamDevice = false,
			aspectRatio = 16.0 / 9.0,
			resolution = 32,
		) shouldBe BlurHashRequest(
			blurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
			width = 32,
			height = 18,
		)
	}

	test("portrait images preserve their aspect ratio in the decode request") {
		createBlurHashRequest(
			url = "https://example.test/poster",
			blurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
			isLowRamDevice = false,
			aspectRatio = 2.0 / 3.0,
			resolution = 32,
		) shouldBe BlurHashRequest(
			blurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
			width = 21,
			height = 32,
		)
	}

	test("BlurHash-only surfaces can plan an asynchronous decode without a network URL") {
		createBlurHashDecodeRequest(
			blurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
			isLowRamDevice = false,
			aspectRatio = 1.0,
			resolution = 32,
		) shouldBe BlurHashRequest(
			blurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
			width = 32,
			height = 32,
		)
	}

	test("BlurHash work is skipped when it cannot produce a useful placeholder") {
		val validHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"

		listOf(
			createBlurHashRequest(null, validHash, false, 1.0, 32),
			createBlurHashRequest("https://example.test/poster", null, false, 1.0, 32),
			createBlurHashRequest("https://example.test/poster", "invalid", false, 1.0, 32),
			createBlurHashRequest("https://example.test/poster", validHash, true, 1.0, 32),
			createBlurHashRequest("https://example.test/poster", validHash, false, 0.0, 32),
			createBlurHashRequest("https://example.test/poster", validHash, false, 1.0, 0),
		) shouldBe List(6) { null }
	}
})
