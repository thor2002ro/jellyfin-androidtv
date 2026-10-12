package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import org.jellyfin.sdk.model.api.request.GetNextUpRequest

class HomeNextUpCutoffTests : FunSpec({
	test("refresh uses current cutoff and clock while retaining request fields") {
		val now = LocalDateTime.of(2026, 9, 18, 12, 0)
		val query = GetNextUpRequest(limit = 50, enableResumable = false, imageTypeLimit = 1)
		val initial = query.withHomeNextUpCutoff(7, now)
		initial shouldBe query.copy(nextUpDateCutoff = now.minusDays(7))
		initial.withHomeNextUpCutoff(30, now.plusDays(1)) shouldBe
			query.copy(nextUpDateCutoff = now.plusDays(1).minusDays(30))
		initial.withHomeNextUpCutoff(0, now) shouldBe query
		initial.withHomeNextUpCutoff(-1, now) shouldBe query
	}
})
