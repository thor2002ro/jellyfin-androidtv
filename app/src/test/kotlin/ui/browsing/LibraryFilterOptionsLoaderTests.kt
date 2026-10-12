package org.jellyfin.androidtv.ui.browsing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

class LibraryFilterOptionsLoaderTests : FunSpec({
	test("only complete filter choices are cached between dialog opens") {
		val incomplete = LibraryFilterChoices(unavailableSections = setOf(LibraryFilterSection.STUDIOS))
		val complete = LibraryFilterChoices(years = listOf(2024))

		shouldCacheLibraryFilterChoices(incomplete) shouldBe false
		shouldCacheLibraryFilterChoices(complete) shouldBe true
	}

	test("equivalent age ratings become grouped choices without merging TV rating variants") {
		val result = mergeFilterChoiceResults(
			genres = Result.success(emptyList()),
			years = Result.success(emptyList()),
			ratings = Result.success(listOf("16", "16+", "NC16", "18", "18+", "TV-Y7", "TV-Y7-FV")),
			studios = Result.success(emptyList()),
		)

		result.ratings shouldBe listOf(
			LibraryRatingChoice(name = "16+", values = setOf("16", "16+", "NC16")),
			LibraryRatingChoice(name = "18+", values = setOf("18", "18+")),
			LibraryRatingChoice(name = "TV-Y7", values = setOf("TV-Y7")),
			LibraryRatingChoice(name = "TV-Y7-FV", values = setOf("TV-Y7-FV")),
		)
	}

	test("equivalent science fiction labels become one genre choice") {
		val scienceFictionId = UUID.randomUUID()
		val hyphenatedId = UUID.randomUUID()
		val abbreviatedId = UUID.randomUUID()
		val dramaId = UUID.randomUUID()
		val result = mergeFilterChoiceResults(
			genres = Result.success(
				listOf(
					BaseItemDto(id = scienceFictionId, name = "Science Fiction", type = BaseItemKind.GENRE),
					BaseItemDto(id = hyphenatedId, name = "Science-Fiction", type = BaseItemKind.GENRE),
					BaseItemDto(id = abbreviatedId, name = "Sci-Fi", type = BaseItemKind.GENRE),
					BaseItemDto(id = dramaId, name = "Drama", type = BaseItemKind.GENRE),
				),
			),
			years = Result.success(emptyList()),
			ratings = Result.success(emptyList()),
			studios = Result.success(emptyList()),
		)

		result.genres shouldBe listOf(
			LibraryGenreChoice(name = "Drama", ids = setOf(dramaId)),
			LibraryGenreChoice(
				name = "Science Fiction",
				ids = setOf(scienceFictionId, hyphenatedId, abbreviatedId),
			),
		)
	}

	test("failed studios lookup keeps other dynamic choices") {
		val genre = BaseItemDto(id = UUID.randomUUID(), name = "Drama", type = BaseItemKind.GENRE)
		val result = mergeFilterChoiceResults(
			genres = Result.success(listOf(genre)),
			years = Result.success(listOf(2024)),
			ratings = Result.success(listOf("PG-13")),
			studios = Result.failure(IllegalStateException("offline")),
		)

		result.genres shouldBe listOf(LibraryGenreChoice(name = "Drama", ids = setOf(genre.id)))
		result.years shouldBe listOf(2024)
		result.ratings shouldBe listOf(LibraryRatingChoice(name = "PG-13", values = setOf("PG-13")))
		result.unavailableSections.shouldContain(LibraryFilterSection.STUDIOS)
	}

	test("filter sections distinguish empty results from failed lookups") {
		LibraryFilterChoices().availability(LibraryFilterSection.GENRES) shouldBe LibraryFilterSectionAvailability.HIDDEN
		LibraryFilterChoices(
			genres = listOf(LibraryGenreChoice("Drama", setOf(UUID.randomUUID()))),
		).availability(LibraryFilterSection.GENRES) shouldBe LibraryFilterSectionAvailability.AVAILABLE
		LibraryFilterChoices(
			unavailableSections = setOf(LibraryFilterSection.GENRES),
		).availability(LibraryFilterSection.GENRES) shouldBe LibraryFilterSectionAvailability.UNAVAILABLE
	}

	test("video-only filters are hidden for explicit music libraries") {
		supportsVideoFilters(setOf(BaseItemKind.MUSIC_ALBUM)) shouldBe false
		supportsVideoFilters(setOf(BaseItemKind.AUDIO)) shouldBe false
		supportsVideoFilters(setOf(BaseItemKind.MOVIE)) shouldBe true
		supportsVideoFilters(setOf(BaseItemKind.SERIES)) shouldBe true
		supportsVideoFilters(emptySet()) shouldBe true
	}
})
