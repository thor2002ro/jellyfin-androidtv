package org.jellyfin.androidtv.ui.browsing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.model.FilterOptions
import org.jellyfin.androidtv.data.model.PlaybackFilter
import java.util.UUID

class LibraryFilterDialogStateTests : FunSpec({
	test("clearing a draft produces the default filter state") {
		val populatedFilters = FilterOptions(
			playback = PlaybackFilter.UNWATCHED,
			favoriteOnly = true,
		)

		LibraryFilterDialogState(draft = populatedFilters).clear().draft shouldBe FilterOptions()
	}

	test("closing without apply preserves applied filters") {
		val applied = FilterOptions(playback = PlaybackFilter.WATCHED)
		val changed = FilterOptions(favoriteOnly = true)

		LibraryFilterDialogState(applied = applied, draft = changed).dismiss().draft shouldBe applied
	}

	test("toggling a grouped genre selects and clears all server genre ids") {
		val firstId = UUID.randomUUID()
		val secondId = UUID.randomUUID()
		val genreIds = setOf(firstId, secondId)

		val selected = LibraryFilterDialogState().toggleGenre(genreIds)
		selected.draft.genreIds shouldBe genreIds

		selected.toggleGenre(genreIds).draft.genreIds shouldBe emptySet()
	}

	test("grouped genre ids count as one selected choice") {
		val firstId = UUID.randomUUID()
		val secondId = UUID.randomUUID()
		val state = LibraryFilterDialogState(
			draft = FilterOptions(genreIds = setOf(firstId, secondId)),
			choices = LibraryFilterChoices(
				genres = listOf(LibraryGenreChoice("Science Fiction", setOf(firstId, secondId))),
			),
		)

		state.selectedGenreCount() shouldBe 1
	}

	test("toggling a grouped rating selects and clears all server rating values") {
		val ratingValues = setOf("16", "16+", "NC16")

		val selected = LibraryFilterDialogState().toggleRating(ratingValues)
		selected.draft.officialRatings shouldBe ratingValues

		selected.toggleRating(ratingValues).draft.officialRatings shouldBe emptySet()
	}

	test("grouped rating values count as one selected choice") {
		val state = LibraryFilterDialogState(
			draft = FilterOptions(officialRatings = setOf("16", "16+", "NC16")),
			choices = LibraryFilterChoices(
				ratings = listOf(LibraryRatingChoice("16+", setOf("16", "16+", "NC16"))),
			),
		)

		state.selectedRatingCount() shouldBe 1
	}
})
