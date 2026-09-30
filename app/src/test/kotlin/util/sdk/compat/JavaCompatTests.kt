package org.jellyfin.androidtv.util.sdk.compat

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

class JavaCompatTests : FunSpec({
	test("timer copy helpers update only their matching fields") {
		val item = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.PROGRAM,
			timerId = "timer-old",
			seriesTimerId = "series-old",
		)

		item.copyWithTimerId("timer-new").let { updated ->
			updated.timerId shouldBe "timer-new"
			updated.seriesTimerId shouldBe "series-old"
		}
		item.copyWithSeriesTimerId("series-new").let { updated ->
			updated.timerId shouldBe "timer-old"
			updated.seriesTimerId shouldBe "series-new"
		}
	}
})
