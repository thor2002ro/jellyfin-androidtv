package org.jellyfin.androidtv.ui.settings.screen.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.constant.HomeSectionType

class HomeSectionEditorTests : FunSpec({
	test("compacting preserves active order and storage capacity") {
		compactHomeSections(
			listOf(
				HomeSectionType.RESUME,
				HomeSectionType.NONE,
				HomeSectionType.NEXT_UP,
				HomeSectionType.NONE,
			)
		) shouldBe listOf(
			HomeSectionType.RESUME,
			HomeSectionType.NEXT_UP,
			HomeSectionType.NONE,
			HomeSectionType.NONE,
		)
	}

	test("compacting removes duplicate active sections") {
		compactHomeSections(
			listOf(
				HomeSectionType.RESUME,
				HomeSectionType.RESUME,
				HomeSectionType.NEXT_UP,
				HomeSectionType.NONE,
			)
		) shouldBe listOf(
			HomeSectionType.RESUME,
			HomeSectionType.NEXT_UP,
			HomeSectionType.NONE,
			HomeSectionType.NONE,
		)
	}

	test("compacting keeps only one library row presentation") {
		compactHomeSections(
			listOf(
				HomeSectionType.LIBRARY_TILES_SMALL,
				HomeSectionType.LIBRARY_BUTTONS,
				HomeSectionType.RESUME,
				HomeSectionType.NONE,
			)
		) shouldBe listOf(
			HomeSectionType.LIBRARY_TILES_SMALL,
			HomeSectionType.RESUME,
			HomeSectionType.NONE,
			HomeSectionType.NONE,
		)
	}

	test("moving an active section swaps it with its neighbor and compacts gaps") {
		moveHomeSection(
			sections = listOf(
				HomeSectionType.RESUME,
				HomeSectionType.NONE,
				HomeSectionType.NEXT_UP,
				HomeSectionType.LATEST_MEDIA,
			),
			activeIndex = 2,
			offset = -1,
		) shouldBe listOf(
			HomeSectionType.RESUME,
			HomeSectionType.LATEST_MEDIA,
			HomeSectionType.NEXT_UP,
			HomeSectionType.NONE,
		)
	}

	test("moving beyond the active bounds leaves compacted sections unchanged") {
		moveHomeSection(
			sections = listOf(HomeSectionType.RESUME, HomeSectionType.NEXT_UP, HomeSectionType.NONE),
			activeIndex = 0,
			offset = -1,
		) shouldBe listOf(HomeSectionType.RESUME, HomeSectionType.NEXT_UP, HomeSectionType.NONE)

		moveHomeSection(
			sections = listOf(HomeSectionType.RESUME, HomeSectionType.NEXT_UP, HomeSectionType.NONE),
			activeIndex = 1,
			offset = 1,
		) shouldBe listOf(HomeSectionType.RESUME, HomeSectionType.NEXT_UP, HomeSectionType.NONE)
	}

	test("removing a section compacts later rows and retains empty slots") {
		removeHomeSection(
			sections = listOf(
				HomeSectionType.RESUME,
				HomeSectionType.NEXT_UP,
				HomeSectionType.LATEST_MEDIA,
				HomeSectionType.NONE,
			),
			activeIndex = 1,
		) shouldBe listOf(
			HomeSectionType.RESUME,
			HomeSectionType.LATEST_MEDIA,
			HomeSectionType.NONE,
			HomeSectionType.NONE,
		)
	}

	test("setting a section replaces an active row or appends into the first empty slot") {
		val sections = listOf(HomeSectionType.RESUME, HomeSectionType.NEXT_UP, HomeSectionType.NONE)

		setHomeSection(sections, activeIndex = 0, HomeSectionType.LIVE_TV) shouldBe listOf(
			HomeSectionType.LIVE_TV,
			HomeSectionType.NEXT_UP,
			HomeSectionType.NONE,
		)
		setHomeSection(sections, activeIndex = 2, HomeSectionType.LATEST_MEDIA) shouldBe listOf(
			HomeSectionType.RESUME,
			HomeSectionType.NEXT_UP,
			HomeSectionType.LATEST_MEDIA,
		)
	}

	test("setting a section cannot create a duplicate row") {
		val sections = listOf(HomeSectionType.RESUME, HomeSectionType.NEXT_UP, HomeSectionType.NONE)

		setHomeSection(sections, activeIndex = 1, HomeSectionType.RESUME) shouldBe sections
	}

	test("section choices contain the current row and unused row types only") {
		val choices = availableHomeSectionTypes(
			sections = listOf(
				HomeSectionType.RESUME,
				HomeSectionType.NEXT_UP,
				HomeSectionType.NONE,
			),
			activeIndex = 1,
		)

		(HomeSectionType.NEXT_UP in choices) shouldBe true
		(HomeSectionType.RESUME in choices) shouldBe false
		(HomeSectionType.NONE in choices) shouldBe false
	}

	test("section choices do not offer a second library row presentation") {
		val choices = availableHomeSectionTypes(
			sections = listOf(HomeSectionType.LIBRARY_TILES_SMALL, HomeSectionType.RESUME, HomeSectionType.NONE),
			activeIndex = 1,
		)

		(HomeSectionType.LIBRARY_TILES_SMALL in choices) shouldBe false
		(HomeSectionType.LIBRARY_BUTTONS in choices) shouldBe false
	}
})
