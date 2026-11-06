package org.jellyfin.androidtv.ui.livetv

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.preference.LiveTvPreferences
import org.jellyfin.androidtv.preference.constant.LiveTvChannelOrder
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

fun BaseItemDto.copyWithLastPlayedDate(
	lastPlayedDate: LocalDateTime,
) = copy(
	userData = userData?.copy(
		lastPlayedDate = lastPlayedDate,
	)
)

internal val liveTvChannelFields = setOf(
	ItemFields.OVERVIEW,
)

fun loadLiveTvChannels(fragment: Fragment, callback: (channels: Collection<BaseItemDto>?) -> Unit) {
	val liveTvPreferences by fragment.inject<LiveTvPreferences>()
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		val channelOrder = LiveTvChannelOrder.fromString(liveTvPreferences[LiveTvPreferences.channelOrder])
		val favoritesAtTop = liveTvPreferences[LiveTvPreferences.favsAtTop]

		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getLiveTvChannels(
					addCurrentProgram = true,
					fields = liveTvChannelFields,
					enableFavoriteSorting = channelOrder.usesServerFavoriteSorting(favoritesAtTop),
					sortBy = setOf(channelOrder.toLiveTvItemSortBy()),
					sortOrder = channelOrder.toLiveTvSortOrder(),
				).content.items.let { channels ->
					channels.sortedByLiveTvChannelOrder(channelOrder, favoritesAtTop)
				}
			}
		}.fold(
			onSuccess = { channels -> callback(channels) },
			onFailure = { callback(null) },
		)
	}
}

internal fun LiveTvChannelOrder.toLiveTvItemSortBy(): ItemSortBy = when (this) {
	LiveTvChannelOrder.LAST_PLAYED -> ItemSortBy.DATE_PLAYED
	LiveTvChannelOrder.CHANNEL_NUMBER -> ItemSortBy.SORT_NAME
	LiveTvChannelOrder.CHANNEL_NAME -> ItemSortBy.NAME
}

internal fun LiveTvChannelOrder.toLiveTvSortOrder(): SortOrder = when (this) {
	LiveTvChannelOrder.LAST_PLAYED -> SortOrder.DESCENDING
	LiveTvChannelOrder.CHANNEL_NUMBER,
	LiveTvChannelOrder.CHANNEL_NAME -> SortOrder.ASCENDING
}

internal fun LiveTvChannelOrder.usesServerFavoriteSorting(
	favoritesAtTop: Boolean,
) = this == LiveTvChannelOrder.LAST_PLAYED && favoritesAtTop

fun Collection<BaseItemDto>.sortedByLiveTvChannelOrder(
	channelOrder: LiveTvChannelOrder,
	favoritesAtTop: Boolean,
): List<BaseItemDto> = when (channelOrder) {
	LiveTvChannelOrder.LAST_PLAYED -> toList()
	LiveTvChannelOrder.CHANNEL_NUMBER -> sortedWithLiveTvChannelComparator(favoritesAtTop, ::compareLiveTvChannelNumbers)
	LiveTvChannelOrder.CHANNEL_NAME -> sortedWithLiveTvChannelComparator(favoritesAtTop, ::compareLiveTvChannelNames)
}

private fun Collection<BaseItemDto>.sortedWithLiveTvChannelComparator(
	favoritesAtTop: Boolean,
	compareChannels: (BaseItemDto, BaseItemDto) -> Int,
): List<BaseItemDto> = sortedWith { left, right ->
	if (favoritesAtTop) {
		val leftFavorite = left.userData?.isFavorite == true
		val rightFavorite = right.userData?.isFavorite == true
		if (leftFavorite != rightFavorite) return@sortedWith if (leftFavorite) -1 else 1
	}

	compareChannels(left, right)
}

private fun compareLiveTvChannelNumbers(left: BaseItemDto, right: BaseItemDto): Int {
	val leftParts = left.number.channelNumberParts()
	val rightParts = right.number.channelNumberParts()

	if (leftParts.isNotEmpty() || rightParts.isNotEmpty()) {
		if (leftParts.isEmpty()) return 1
		if (rightParts.isEmpty()) return -1

		val maxPartCount = maxOf(leftParts.size, rightParts.size)
		for (index in 0 until maxPartCount) {
			val leftPart = leftParts.getOrNull(index)
			val rightPart = rightParts.getOrNull(index)

			when {
				leftPart == null -> return -1
				rightPart == null -> return 1
				leftPart != rightPart -> return leftPart.compareTo(rightPart)
			}
		}
	}

	return compareValuesBy(
		left,
		right,
		{ channel -> channel.number.orEmpty().lowercase() },
		{ channel -> channel.name.orEmpty().lowercase() },
	)
}

private fun compareLiveTvChannelNames(left: BaseItemDto, right: BaseItemDto): Int = compareValuesBy(
	left,
	right,
	{ channel -> channel.nameSortKey().lowercase() },
	{ channel -> channel.number.orEmpty().lowercase() },
)

private val channelNumberPartRegex = Regex("\\d+")

private fun String?.channelNumberParts(): List<Int> = channelNumberPartRegex
	.findAll(orEmpty())
	.mapNotNull { match -> match.value.toIntOrNull() }
	.toList()

private fun BaseItemDto.nameSortKey() = name
	?.takeIf { value -> value.isNotBlank() }
	?: sortName.orEmpty()

fun getPrograms(
	fragment: Fragment,
	channelIds: Array<UUID>,
	startTime: LocalDateTime,
	endTime: LocalDateTime,
	callback: (programs: Collection<BaseItemDto>?) -> Unit,
) {
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getLiveTvPrograms(
					channelIds = channelIds.toList(),
					enableImages = false,
					sortBy = setOf(ItemSortBy.START_DATE),
					maxStartDate = endTime,
					minEndDate = startTime,
				).content.items
			}
		}.fold(
			onSuccess = { programs -> callback(programs) },
			onFailure = { callback(null) },
		)
	}
}

fun getScheduleRows(
	fragment: Fragment,
	seriesTimerId: String?,
	callback: (timers: Map<LocalDate, List<BaseItemDto>>?) -> Unit,
) {
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getTimers(
					seriesTimerId = seriesTimerId,
				).content.items
			}
		}.fold(
			onSuccess = { timers ->
				val groupedTimers = timers
					.filterNot { it.startDate == null }
					.map { timer ->
						timer.programInfo ?: BaseItemDto(
							id = requireNotNull(timer.id?.toUUIDOrNull()),
							channelName = timer.channelName,
							name = timer.name.orEmpty(),
							type = BaseItemKind.PROGRAM,
							mediaType = MediaType.UNKNOWN,
							timerId = timer.id,
							seriesTimerId = timer.seriesTimerId,
							startDate = timer.startDate,
							endDate = timer.endDate,
						)
					}
					.map { it.copy(locationType = LocationType.VIRTUAL) }
					.groupBy { it.startDate!!.toLocalDate() }

				callback(groupedTimers)
			},
			onFailure = {
				callback(null)
			},
		)
	}
}
