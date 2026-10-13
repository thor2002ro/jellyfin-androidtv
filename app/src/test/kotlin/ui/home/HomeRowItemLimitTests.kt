package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HomeRowItemLimitTests : FunSpec({
	test("configured row limit is preserved within the supported range") {
		effectiveHomeRowItemLimit(configuredLimit = 20, maximum = 50) shouldBe 20
	}

	test("row limit is clamped to safe request bounds") {
		effectiveHomeRowItemLimit(configuredLimit = 1, maximum = 50) shouldBe 5
		effectiveHomeRowItemLimit(configuredLimit = 50, maximum = 40) shouldBe 40
		effectiveHomeRowItemLimit(configuredLimit = 100, maximum = 50) shouldBe 50
	}
})
