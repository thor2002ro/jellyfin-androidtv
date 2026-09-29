package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DeviceGraphicsInfoTest : FunSpec({
	test("Vulkan feature versions use Android's packed version format") {
		formatVulkanVersion((1 shl 22) or (3 shl 12) or 275) shouldBe "1.3.275"
	}

	test("reported OpenGL version does not understate device support") {
		selectOpenGlVersion("OpenGL ES 2.0 vendor", "OpenGL ES 3.2") shouldBe "OpenGL ES 3.2"
	}

	test("API versions reuse the detected device graphics information") {
		val info = DeviceGraphicsInfo(
			gpuName = "GPU",
			socName = null,
			openGlVersion = "OpenGL ES 3.2",
			vulkanApiVersion = (1 shl 22) or (3 shl 12),
		)

		info.apiVersion("opengl") shouldBe "ES 3.2"
		info.apiVersion("vulkan") shouldBe "1.3.0"
		info.apiVersion("unknown") shouldBe null
	}
})
