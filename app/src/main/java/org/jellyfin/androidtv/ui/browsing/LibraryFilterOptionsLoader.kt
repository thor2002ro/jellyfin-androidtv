package org.jellyfin.androidtv.ui.browsing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.filterApi
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.api.client.extensions.studioApi
import org.jellyfin.sdk.api.client.extensions.yearApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.Locale
import java.util.UUID

enum class LibraryFilterSection {
	GENRES,
	YEARS,
	RATINGS,
	STUDIOS,
}

enum class LibraryFilterSectionAvailability {
	HIDDEN,
	AVAILABLE,
	UNAVAILABLE,
}

data class LibraryGenreChoice(
	val name: String,
	val ids: Set<UUID>,
)

data class LibraryRatingChoice(
	val name: String,
	val values: Set<String>,
)

data class LibraryFilterChoices(
	val genres: List<LibraryGenreChoice> = emptyList(),
	val years: List<Int> = emptyList(),
	val ratings: List<LibraryRatingChoice> = emptyList(),
	val studios: List<BaseItemDto> = emptyList(),
	val unavailableSections: Set<LibraryFilterSection> = emptySet(),
)

internal fun LibraryFilterChoices.availability(section: LibraryFilterSection): LibraryFilterSectionAvailability = when {
	section in unavailableSections -> LibraryFilterSectionAvailability.UNAVAILABLE
	when (section) {
		LibraryFilterSection.GENRES -> genres.isNotEmpty()
		LibraryFilterSection.YEARS -> years.isNotEmpty()
		LibraryFilterSection.RATINGS -> ratings.isNotEmpty()
		LibraryFilterSection.STUDIOS -> studios.isNotEmpty()
	} -> LibraryFilterSectionAvailability.AVAILABLE
	else -> LibraryFilterSectionAvailability.HIDDEN
}

internal fun supportsVideoFilters(includeTypes: Set<BaseItemKind>): Boolean = includeTypes.isEmpty() || includeTypes.any {
	it in VIDEO_FILTER_ITEM_TYPES
}

private val VIDEO_FILTER_ITEM_TYPES = setOf(
	BaseItemKind.EPISODE,
	BaseItemKind.LIVE_TV_PROGRAM,
	BaseItemKind.MOVIE,
	BaseItemKind.MUSIC_VIDEO,
	BaseItemKind.PROGRAM,
	BaseItemKind.SERIES,
	BaseItemKind.TRAILER,
	BaseItemKind.TV_PROGRAM,
	BaseItemKind.VIDEO,
)

internal fun mergeFilterChoiceResults(
	genres: Result<List<BaseItemDto>>,
	years: Result<List<Int>>,
	ratings: Result<List<String>>,
	studios: Result<List<BaseItemDto>>,
): LibraryFilterChoices = mergeFilterChoiceRefresh(null, genres, years, ratings, studios)

internal fun mergeFilterChoiceRefresh(
	previous: LibraryFilterChoices?,
	genres: Result<List<BaseItemDto>>?,
	years: Result<List<Int>>?,
	ratings: Result<List<String>>?,
	studios: Result<List<BaseItemDto>>?,
): LibraryFilterChoices = LibraryFilterChoices(
	genres = genres?.getOrNull()?.groupEquivalentGenres() ?: previous?.genres.orEmpty(),
	years = years?.getOrNull() ?: previous?.years.orEmpty(),
	ratings = ratings?.getOrNull()?.groupEquivalentRatings() ?: previous?.ratings.orEmpty(),
	studios = studios?.getOrNull() ?: previous?.studios.orEmpty(),
	unavailableSections = buildSet {
		previous?.unavailableSections?.forEach { section ->
			val wasRetried = when (section) {
				LibraryFilterSection.GENRES -> genres != null
				LibraryFilterSection.YEARS -> years != null
				LibraryFilterSection.RATINGS -> ratings != null
				LibraryFilterSection.STUDIOS -> studios != null
			}
			if (!wasRetried) add(section)
		}
		if (genres?.isFailure == true) add(LibraryFilterSection.GENRES)
		if (years?.isFailure == true) add(LibraryFilterSection.YEARS)
		if (ratings?.isFailure == true) add(LibraryFilterSection.RATINGS)
		if (studios?.isFailure == true) add(LibraryFilterSection.STUDIOS)
	},
)

private fun List<BaseItemDto>.groupEquivalentGenres(): List<LibraryGenreChoice> =
	filter { !it.name.isNullOrBlank() }
		.groupBy { canonicalGenreName(it.name.orEmpty()) }
		.values
		.map { genres ->
			LibraryGenreChoice(
				name = genres.mapNotNull(BaseItemDto::name).preferredLabel(),
				ids = genres.mapTo(linkedSetOf(), BaseItemDto::id),
			)
		}
		.sortedBy { it.name.lowercase(Locale.ROOT) }

private fun canonicalGenreName(name: String): String {
	val normalized = name.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
	return GENRE_ALIASES[normalized] ?: normalized
}

private fun List<String>.groupEquivalentRatings(): List<LibraryRatingChoice> =
	filter(String::isNotBlank)
		.groupBy(::canonicalRatingName)
		.map { (canonicalName, ratings) ->
			LibraryRatingChoice(
				name = ratings.preferredRatingName(canonicalName),
				values = ratings.toCollection(linkedSetOf()),
			)
		}
		.sortedBy { it.name.lowercase(Locale.ROOT) }

private fun canonicalRatingName(name: String): String {
	val normalized = name.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
	return RATING_ALIASES[normalized] ?: normalized
}

private fun List<String>.preferredRatingName(canonicalName: String): String =
	firstOrNull { it.trim().endsWith('+') && canonicalRatingName(it) == canonicalName }
		?: preferredLabel()

private fun List<String>.preferredLabel(): String = minWith(
	compareBy<String>(
		{ -it.length },
		{ name -> name.count { character -> !character.isLetterOrDigit() && !character.isWhitespace() } },
		{ it.lowercase(Locale.ROOT) },
	)
)

private val GENRE_ALIASES = mapOf(
	"scifi" to "sciencefiction",
)

private val RATING_ALIASES = mapOf(
	"nc16" to "16",
)

suspend fun loadLibraryFilterChoices(
	api: ApiClient,
	parentId: UUID,
	includeTypes: Set<BaseItemKind>,
	previous: LibraryFilterChoices? = null,
): LibraryFilterChoices = coroutineScope {
	val sectionsToLoad = previous?.unavailableSections ?: LibraryFilterSection.entries.toSet()
	val genres = if (LibraryFilterSection.GENRES in sectionsToLoad) async(Dispatchers.IO) {
		runCatching {
			api.genreApi.getGenres(
				parentId = parentId,
				includeItemTypes = includeTypes,
				sortBy = setOf(ItemSortBy.SORT_NAME),
				sortOrder = setOf(SortOrder.ASCENDING),
				enableImages = false,
				enableTotalRecordCount = false,
			).content.items.filter { !it.name.isNullOrBlank() }
		}
	} else null
	val years = if (LibraryFilterSection.YEARS in sectionsToLoad) async(Dispatchers.IO) {
		runCatching {
			api.yearApi.getYears(
				parentId = parentId,
				includeItemTypes = includeTypes,
				sortOrder = setOf(SortOrder.DESCENDING),
				enableImages = false,
			).content.items.mapNotNull { item ->
				item.productionYear ?: item.name?.toIntOrNull()
			}.distinct().sortedDescending()
		}
	} else null
	val ratings = if (LibraryFilterSection.RATINGS in sectionsToLoad) async(Dispatchers.IO) {
		runCatching {
			api.filterApi.getQueryFiltersLegacy(
				parentId = parentId,
				includeItemTypes = includeTypes,
			).content.officialRatings.orEmpty().filter(String::isNotBlank).distinct().sorted()
		}
	} else null
	val studios = if (LibraryFilterSection.STUDIOS in sectionsToLoad) async(Dispatchers.IO) {
		runCatching {
			api.studioApi.getStudios(
				parentId = parentId,
				includeItemTypes = includeTypes,
				enableImages = false,
				enableTotalRecordCount = false,
			).content.items.filter { !it.name.isNullOrBlank() }.sortedBy { it.name }
		}
	} else null

	mergeFilterChoiceRefresh(
		previous = previous,
		genres = genres?.await(),
		years = years?.await(),
		ratings = ratings?.await(),
		studios = studios?.await(),
	)
}
