package org.jellyfin.androidtv.data.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.constant.LibraryCardSpacing
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.VideoType
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID

class LibraryFilterOptionsTests : FunSpec({
	val firstId = UUID.fromString("10000000-0000-0000-0000-000000000001")
	val secondId = UUID.fromString("20000000-0000-0000-0000-000000000002")

	test("filter encoding round trips in deterministic order") {
		val filters = FilterOptions(
			playback = PlaybackFilter.WATCHED,
			favoriteOnly = true,
			genreIds = setOf(secondId, firstId),
			years = setOf(2024, 1999),
			officialRatings = setOf("R", "PG-13"),
			studioIds = setOf(secondId),
			quality = QualityFilter.FOUR_K,
			videoTypes = setOf(VideoType.BLU_RAY, VideoType.VIDEO_FILE),
		)

		FilterOptions.decode(filters.encode()) shouldBe filters
		filters.encode() shouldBe filters.copy(
			genreIds = filters.genreIds.reversed().toSet(),
			years = filters.years.reversed().toSet(),
		).encode()
	}

	test("4k request conversion does not also force generic hd") {
		val parentId = UUID.randomUUID()
		val request = FilterOptions(quality = QualityFilter.FOUR_K)
			.applyTo(GetItemsRequest(parentId = parentId, recursive = true))

		request.is4k shouldBe true
		request.isHd shouldBe null
		request.parentId shouldBe parentId
		request.recursive shouldBe true
	}

	test("request conversion replaces managed filters and preserves unrelated fields") {
		val filters = FilterOptions(
			playback = PlaybackFilter.UNWATCHED,
			favoriteOnly = true,
			genreIds = setOf(firstId),
			years = setOf(2024),
			officialRatings = setOf("PG-13"),
			studioIds = setOf(secondId),
			quality = QualityFilter.HD,
			videoTypes = setOf(VideoType.VIDEO_FILE),
		)
		val request = filters.applyTo(
			GetItemsRequest(
				filters = setOf(ItemFilter.IS_RESUMABLE, ItemFilter.IS_PLAYED),
				searchTerm = "matrix",
			)
		)

		request.filters.orEmpty().shouldContainExactlyInAnyOrder(
			ItemFilter.IS_RESUMABLE,
			ItemFilter.IS_UNPLAYED,
			ItemFilter.IS_FAVORITE,
		)
		request.genreIds shouldBe setOf(firstId)
		request.years shouldBe setOf(2024)
		request.officialRatings shouldBe setOf("PG-13")
		request.studioIds shouldBe setOf(secondId)
		request.isHd shouldBe true
		request.is4k shouldBe false
		request.videoTypes shouldBe setOf(VideoType.VIDEO_FILE)
		request.searchTerm shouldBe "matrix"
	}

	test("legacy unwatched and favorite settings migrate") {
		FilterOptions.fromStored(
			encoded = "",
			legacyFavoriteOnly = true,
			legacyUnwatchedOnly = true,
		) shouldBe FilterOptions(
			playback = PlaybackFilter.UNWATCHED,
			favoriteOnly = true,
		)
	}

	test("malformed stored filters fall back to no filters") {
		FilterOptions.decode("{not-json") shouldBe FilterOptions()
	}

	test("active count treats each filter category as one and clear resets all") {
		val filters = FilterOptions(
			playback = PlaybackFilter.WATCHED,
			favoriteOnly = true,
			genreIds = setOf(firstId, secondId),
			years = setOf(1999, 2000),
		)

		filters.activeCount shouldBe 4
		filters.isEmpty shouldBe false
		filters.clear() shouldBe FilterOptions()
		filters.clear().isEmpty shouldBe true
	}

	test("decade shortcut expands only available years") {
		expandDecade(1990, setOf(1989, 1990, 1994, 1999, 2000)) shouldBe setOf(1990, 1994, 1999)
	}

	test("spacing changes only the supplied inter-item gap") {
		LibraryCardSpacing.COMPACT.apply(8) shouldBe 6
		LibraryCardSpacing.NORMAL.apply(8) shouldBe 8
		LibraryCardSpacing.RELAXED.apply(8) shouldBe 10
		LibraryCardSpacing.COMPACT.apply(1) shouldBe 2
	}

})
