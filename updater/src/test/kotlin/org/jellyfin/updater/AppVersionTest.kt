package org.jellyfin.updater

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AppVersionTest : FunSpec({
	test("date versions compare using hour and minute") {
		(AppVersion.parse("v2026.07.01.2134")!! > AppVersion.parse("v2026.07.01.1920")!!) shouldBe true
	}

	test("semantic versions still compare") {
		(AppVersion.parse("v2.1.0")!! > AppVersion.parse("v2.0.9")!!) shouldBe true
	}
})
