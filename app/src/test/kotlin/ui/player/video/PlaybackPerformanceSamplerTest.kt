package org.jellyfin.androidtv.ui.player.video

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlaybackPerformanceSamplerTest : FunSpec({
	test("performance metrics use units defined by their source") {
		64f.asGpuPercent() shouldBe 64f
		101f.asGpuPercent() shouldBe null
		85f.asHardwareTemperatureCelsius() shouldBe 85f
		85_000f.asThermalZoneTemperatureCelsius() shouldBe 85f
	}
})
