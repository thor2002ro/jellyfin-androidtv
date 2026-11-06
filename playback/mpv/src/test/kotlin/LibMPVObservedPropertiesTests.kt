package org.jellyfin.playback.mpv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LibMPVObservedPropertiesTests : FunSpec({
	test("observed values update without querying MPV and unavailable values are removed") {
		val properties = LibMPVObservedProperties()
		properties.string("time-pos") shouldBe null
		properties.update("time-pos", "12.5")
		properties.double("time-pos") shouldBe 12.5
		properties.update("time-pos", "13.5")
		properties.double("time-pos") shouldBe 13.5
		properties.update("time-pos", null)
		properties.double("time-pos") shouldBe null
	}
	test("flags and counters retain their representation and recreation clears old values") {
		val properties = LibMPVObservedProperties()
		properties.update("paused-for-cache", "yes")
		properties.update("frame-drop-count", "42")
		properties.string("paused-for-cache") shouldBe "yes"
		properties.integer("frame-drop-count") shouldBe 42
		properties.clear()
		properties.string("paused-for-cache") shouldBe null
		properties.integer("frame-drop-count") shouldBe null
	}
})
