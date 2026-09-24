package org.jellyfin.androidtv.util.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

private const val TICKS_PER_MINUTE = 60 * 10_000_000L

class BaseItemExtensionsTests : FunSpec({
	test("buildChapterItems creates virtual chapters every five minutes") {
		val item = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.MOVIE,
			runTimeTicks = 13 * TICKS_PER_MINUTE,
		)

		val chapters = item.buildChapterItems()

		chapters.map { it.name } shouldBe listOf("Chapter 1", "Chapter 2", "Chapter 3")
		chapters.map { it.startPositionTicks } shouldBe listOf(0L, 5 * TICKS_PER_MINUTE, 10 * TICKS_PER_MINUTE)
	}
})
