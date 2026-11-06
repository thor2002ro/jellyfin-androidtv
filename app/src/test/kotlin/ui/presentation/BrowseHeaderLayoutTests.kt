package org.jellyfin.androidtv.ui.presentation

import androidx.compose.foundation.gestures.BringIntoViewSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BrowseHeaderLayoutTests : FunSpec({
	val visibleBounds = object : BringIntoViewSpec {
		override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
			offset < 0f -> offset
			offset + size > containerSize -> offset + size - containerSize
			else -> 0f
		}
	}

	test("focused cards behind the header scroll back into the visible area") {
		BrowseHeaderBringIntoViewSpec(visibleBounds, 100f)
			.calculateScrollDistance(60f, 80f, 500f) shouldBe -40f
	}

	test("visible cards stay put and bottom cards retain the normal scroll distance") {
		val spec = BrowseHeaderBringIntoViewSpec(visibleBounds, 100f)
		spec.calculateScrollDistance(120f, 80f, 500f) shouldBe 0f
		spec.calculateScrollDistance(450f, 100f, 500f) shouldBe 50f
	}

	test("a layout without a header keeps its original focus scroll behavior") {
		BrowseHeaderBringIntoViewSpec(visibleBounds, 0f)
			.calculateScrollDistance(60f, 80f, 500f) shouldBe 0f
	}
})
