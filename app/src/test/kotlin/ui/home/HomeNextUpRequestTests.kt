package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HomeNextUpRequestTests : FunSpec({
	test("next up keeps resumable items separate and rewatching disabled by default") {
		val request = createHomeNextUpRequest(itemLimit = 50, includeRewatching = false)

		request.limit shouldBe 50
		request.enableResumable shouldBe false
		request.enableRewatching shouldBe false
	}

	test("next up rewatching can be enabled without bypassing the row limit") {
		val request = createHomeNextUpRequest(itemLimit = 100, includeRewatching = true)

		request.limit shouldBe 50
		request.enableResumable shouldBe false
		request.enableRewatching shouldBe true
	}

	test("combined continue watching includes resumable items in next up") {
		val request = createHomeNextUpRequest(
			itemLimit = 50,
			includeRewatching = false,
			includeResumable = true,
		)

		request.limit shouldBe 50
		request.enableResumable shouldBe true
	}
})
