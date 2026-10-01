package org.jellyfin.androidtv.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.VideoType
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID

enum class PlaybackFilter {
	ANY,
	UNWATCHED,
	WATCHED,
}

enum class QualityFilter {
	ANY,
	SD,
	HD,
	FOUR_K,
}

data class FilterOptions @JvmOverloads constructor(
	val playback: PlaybackFilter = PlaybackFilter.ANY,
	val favoriteOnly: Boolean = false,
	val genreIds: Set<UUID> = emptySet(),
	val years: Set<Int> = emptySet(),
	val officialRatings: Set<String> = emptySet(),
	val studioIds: Set<UUID> = emptySet(),
	val quality: QualityFilter = QualityFilter.ANY,
	val videoTypes: Set<VideoType> = emptySet(),
) {
	val activeCount: Int
		get() = listOf(
			playback != PlaybackFilter.ANY,
			favoriteOnly,
			genreIds.isNotEmpty(),
			years.isNotEmpty(),
			officialRatings.isNotEmpty(),
			studioIds.isNotEmpty(),
			quality != QualityFilter.ANY,
			videoTypes.isNotEmpty(),
		).count { it }

	val isEmpty: Boolean
		get() = activeCount == 0

	val filters: Set<ItemFilter>
		get() = buildSet {
			when (playback) {
				PlaybackFilter.ANY -> Unit
				PlaybackFilter.UNWATCHED -> add(ItemFilter.IS_UNPLAYED)
				PlaybackFilter.WATCHED -> add(ItemFilter.IS_PLAYED)
			}
			if (favoriteOnly) add(ItemFilter.IS_FAVORITE)
		}

	fun isFavoriteOnly(): Boolean = favoriteOnly

	fun isUnwatchedOnly(): Boolean = playback == PlaybackFilter.UNWATCHED

	fun isWatchedOnly(): Boolean = playback == PlaybackFilter.WATCHED

	fun withFavoriteOnly(enabled: Boolean): FilterOptions = copy(favoriteOnly = enabled)

	fun withUnwatchedOnly(enabled: Boolean): FilterOptions = copy(
		playback = if (enabled) PlaybackFilter.UNWATCHED else PlaybackFilter.ANY,
	)

	fun clear(): FilterOptions = FilterOptions()

	fun applyTo(request: GetItemsRequest): GetItemsRequest {
		val unmanagedFilters = request.filters.orEmpty() - MANAGED_ITEM_FILTERS
		val (isHd, is4k) = when (quality) {
			QualityFilter.ANY -> null to null
			QualityFilter.SD -> false to null
			QualityFilter.HD -> true to false
			QualityFilter.FOUR_K -> null to true
		}

		return request.copy(
			filters = (unmanagedFilters + filters).takeIf { it.isNotEmpty() },
			genreIds = genreIds.takeIf { it.isNotEmpty() },
			years = years.takeIf { it.isNotEmpty() },
			officialRatings = officialRatings.takeIf { it.isNotEmpty() },
			studioIds = studioIds.takeIf { it.isNotEmpty() },
			isHd = isHd,
			is4k = is4k,
			videoTypes = videoTypes.takeIf { it.isNotEmpty() },
		)
	}

	fun encode(): String = Json.encodeToString(
		StoredFilterOptions(
			playback = playback.name,
			favoriteOnly = favoriteOnly,
			genreIds = genreIds.map(UUID::toString).sorted(),
			years = years.sorted(),
			officialRatings = officialRatings.sorted(),
			studioIds = studioIds.map(UUID::toString).sorted(),
			quality = quality.name,
			videoTypes = videoTypes.map(VideoType::name).sorted(),
		)
	)

	companion object {
		private val MANAGED_ITEM_FILTERS = setOf(
			ItemFilter.IS_FAVORITE,
			ItemFilter.IS_PLAYED,
			ItemFilter.IS_UNPLAYED,
		)

		@JvmStatic
		fun decode(encoded: String): FilterOptions {
			if (encoded.isBlank()) return FilterOptions()
			return runCatching {
				val stored = Json.decodeFromString<StoredFilterOptions>(encoded)
				require(stored.version == STORED_FILTER_VERSION)
				FilterOptions(
					playback = PlaybackFilter.valueOf(stored.playback),
					favoriteOnly = stored.favoriteOnly,
					genreIds = stored.genreIds.mapTo(linkedSetOf(), UUID::fromString),
					years = stored.years.toSet(),
					officialRatings = stored.officialRatings.toSet(),
					studioIds = stored.studioIds.mapTo(linkedSetOf(), UUID::fromString),
					quality = QualityFilter.valueOf(stored.quality),
					videoTypes = stored.videoTypes.mapTo(linkedSetOf(), VideoType::valueOf),
				)
			}.getOrDefault(FilterOptions())
		}

		@JvmStatic
		fun fromStored(
			encoded: String,
			legacyFavoriteOnly: Boolean,
			legacyUnwatchedOnly: Boolean,
		): FilterOptions = if (encoded.isNotBlank()) {
			decode(encoded)
		} else {
			FilterOptions(
				playback = if (legacyUnwatchedOnly) PlaybackFilter.UNWATCHED else PlaybackFilter.ANY,
				favoriteOnly = legacyFavoriteOnly,
			)
		}
	}
}

@Serializable
private data class StoredFilterOptions(
	val version: Int = STORED_FILTER_VERSION,
	val playback: String,
	val favoriteOnly: Boolean,
	val genreIds: List<String>,
	val years: List<Int>,
	val officialRatings: List<String>,
	val studioIds: List<String>,
	val quality: String,
	val videoTypes: List<String>,
)

fun expandDecade(startYear: Int, availableYears: Set<Int>): Set<Int> =
	availableYears.filterTo(linkedSetOf()) { it in startYear until startYear + YEARS_PER_DECADE }

private const val STORED_FILTER_VERSION = 1
private const val YEARS_PER_DECADE = 10
