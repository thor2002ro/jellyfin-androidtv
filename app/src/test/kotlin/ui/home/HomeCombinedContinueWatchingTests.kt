package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.constant.HomeSectionType

class HomeCombinedContinueWatchingTests : FunSpec({
	test("combining replaces the first continue watching or next up section and removes the other") {
		val layout = createHomeSectionLayout(
			sections = listOf(
				HomeSectionType.LIBRARY_TILES_SMALL,
				HomeSectionType.NEXT_UP,
				HomeSectionType.LATEST_MEDIA,
				HomeSectionType.RESUME,
			),
			combineContinueWatchingAndNextUp = true,
		)

		layout.sections shouldBe listOf(
			HomeSectionType.LIBRARY_TILES_SMALL,
			HomeSectionType.RESUME,
			HomeSectionType.LATEST_MEDIA,
		)
		layout.combineContinueWatchingAndNextUp shouldBe true
	}

	test("combining leaves a single configured section unchanged") {
		val layout = createHomeSectionLayout(
			sections = listOf(HomeSectionType.RESUME, HomeSectionType.LATEST_MEDIA),
			combineContinueWatchingAndNextUp = true,
		)

		layout.sections shouldBe listOf(HomeSectionType.RESUME, HomeSectionType.LATEST_MEDIA)
		layout.combineContinueWatchingAndNextUp shouldBe false
	}

	test("disabled combining preserves separate section order") {
		val layout = createHomeSectionLayout(
			sections = listOf(HomeSectionType.NEXT_UP, HomeSectionType.RESUME),
			combineContinueWatchingAndNextUp = false,
		)

		layout.sections shouldBe listOf(HomeSectionType.NEXT_UP, HomeSectionType.RESUME)
		layout.combineContinueWatchingAndNextUp shouldBe false
	}
})
