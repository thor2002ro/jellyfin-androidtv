package org.jellyfin.androidtv.ui.settings.compat

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PreferenceSaveGuardTests : FunSpec({
	test("a preference screen ignores another save until the active save finishes") {
		val guard = PreferenceSaveGuard()

		guard.tryStart() shouldBe true
		guard.tryStart() shouldBe false

		guard.finish()
		guard.tryStart() shouldBe true
	}
})
