package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.constant.CustomMessage

class HomeConfigurationRefreshTests : FunSpec({
	test("structural Home changes recreate the rows") {
		shouldRecreateHomeRows(CustomMessage.RefreshHomeConfiguration) shouldBe true
	}

	test("unrelated messages leave the Home rows intact") {
		shouldRecreateHomeRows(CustomMessage.RefreshCurrentItem) shouldBe false
		shouldRecreateHomeRows(CustomMessage.RefreshHomeNextUp) shouldBe false
		shouldRecreateHomeRows(CustomMessage.ActionComplete) shouldBe false
		shouldRecreateHomeRows(null) shouldBe false
	}
})
