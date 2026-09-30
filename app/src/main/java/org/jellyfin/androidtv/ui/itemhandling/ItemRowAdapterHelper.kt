package org.jellyfin.androidtv.ui.itemhandling

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.LiveTvOption
import org.jellyfin.androidtv.data.querying.GetAdditionalPartsRequest
import org.jellyfin.androidtv.data.querying.GetSpecialsRequest
import org.jellyfin.androidtv.data.querying.GetTrailersRequest
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.browsing.BrowseGridFragment.SortOption
import org.jellyfin.androidtv.ui.livetv.LiveTvTrackCache
import org.jellyfin.androidtv.util.sdk.hasLanguageBadge
import org.jellyfin.androidtv.util.sdk.hasLanguageBadgeStreams
import org.jellyfin.androidtv.util.sdk.hasVideoBadgeMetadata
import org.jellyfin.androidtv.util.sdk.languageBadgeText
import org.jellyfin.androidtv.util.sdk.streamBadgeItemTypes
import org.jellyfin.androidtv.util.sdk.videoBadgeCodecText
import org.jellyfin.androidtv.util.sdk.videoBadgeResolutionText
import org.jellyfin.playback.jellyfin.livetv.liveTvChannelId
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SeriesTimerInfoDto
import org.jellyfin.sdk.model.api.request.GetAlbumArtistsRequest
import org.jellyfin.sdk.model.api.request.GetArtistsRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetLiveTvChannelsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetRecommendedProgramsRequest
import org.jellyfin.sdk.model.api.request.GetRecordingsRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import org.jellyfin.sdk.model.api.request.GetSeasonsRequest
import org.jellyfin.sdk.model.api.request.GetSimilarItemsRequest
import org.jellyfin.sdk.model.api.request.GetUpcomingEpisodesRequest
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

private const val LIVE_TV_CHANNEL_IMAGE_FALLBACK_LIMIT = 300

fun <T : Any> ItemRowAdapter.setItems(
	items: Collection<T>,
	transform: (T, Int) -> BaseRowItem?,
) {
	Timber.d("Creating items from $itemsLoaded existing and ${items.size} new, adapter size is ${size()}")

	val allItems = buildList {
		// Add current items before loaded items
		repeat(itemsLoaded) {
			this@setItems.get(it)?.let(::add)
		}

		// Add loaded items
		val mappedItems = items.mapIndexedNotNull { index, item ->
			transform(item, itemsLoaded + index)
		}
		mappedItems.forEach { add(it) }

		// Add current items after loaded items
		repeat(min(totalItems, size()) - itemsLoaded - mappedItems.size) {
			this@setItems.get(it + itemsLoaded + mappedItems.size)?.let(::add)
		}
	}

	replaceAll(allItems, areItemsTheSame = ::areAdapterItemsTheSame)
	itemsLoaded = allItems.size
	addRowToParentIfResultsReceived()
}

internal fun areAdapterItemsTheSame(old: Any, new: Any): Boolean {
	val oldRowItem = old as? BaseRowItem
	val newRowItem = new as? BaseRowItem
	if (oldRowItem != null || newRowItem != null) {
		val oldItemId = oldRowItem?.itemId ?: return false
		return oldItemId == newRowItem?.itemId
	}

	return old == new
}

internal fun BaseRowItem.resumeSignature() = listOf(
	itemId,
	showRemainingTimeBadge,
	baseItem?.runTimeTicks,
	baseItem?.userData?.played,
	baseItem?.userData?.playedPercentage,
	baseItem?.userData?.playbackPositionTicks,
)

private fun BaseRowItem.itemSignature() = listOf(
	itemId,
	baseItem?.name,
	baseItem?.episodeTitle,
	baseItem?.userData?.played,
	baseItem?.userData?.playedPercentage,
	baseItem?.userData?.playbackPositionTicks,
)

private fun BaseRowItem.liveTvProgramSignature() = listOf(
	itemId,
	baseItem?.name,
	baseItem?.episodeTitle,
	baseItem?.channelId,
	baseItem?.channelName,
	baseItem?.channelNumber,
	baseItem?.channelPrimaryImageTag,
	baseItem?.startDate,
	baseItem?.endDate,
)

internal fun GetItemsRequest.showsRemainingTimeBadges() = filters?.contains(ItemFilter.IS_RESUMABLE) == true

private fun ItemRowAdapter.replaceIfChanged(items: List<BaseRowItem>, signature: (BaseRowItem) -> List<Any?>) {
	val oldItems = List(size()) { index -> get(index) as? BaseRowItem }
	if (oldItems.map { it?.let(signature) } != items.map(signature)) replaceAll(items)
	itemsLoaded = items.size
	totalItems = items.size
	addRowToParentIfResultsReceived()
}

fun ItemRowAdapter.retrieveResumeItems(api: ApiClient, query: GetResumeItemsRequest) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.itemsApi.getResumeItems(query).content
			}

			val items = response.items.map {
				it.toBaseItemRowItem(
					preferParentThumb = preferParentThumb,
					staticHeight = isStaticHeight,
					showRemainingTimeBadge = true,
				)
			}

			replaceIfChanged(items, BaseRowItem::resumeSignature)
			if (items.isEmpty()) removeRow()
		}.fold(
			onSuccess = { notifyRetrieveFinished() },
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

fun ItemRowAdapter.retrieveNextUpItems(api: ApiClient, query: GetNextUpRequest) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		var displayedItems = emptyList<BaseItemDto>()
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.tvShowsApi.getNextUp(query).content
			}

			// Some special flavor for series, used in FullDetailsFragment
			val firstNextUp = response.items.firstOrNull()
			if (query.seriesId != null && response.items.size == 1 && firstNextUp?.seasonId != null && firstNextUp.indexNumber != null) {
				// If we have exactly 1 episode returned, the series is currently partially watched
				// we want to query the server for all episodes in the same season starting from
				// this one to create a list of all unwatched episodes
				val episodesResponse = withContext(Dispatchers.IO) {
					api.itemsApi.getItems(
						parentId = firstNextUp.seasonId,
						startIndex = firstNextUp.indexNumber,
					).content
				}

				// Combine the next up episode with the additionally retrieved episodes
				val items = buildList {
					add(firstNextUp)
					addAll(episodesResponse.items)
				}
				displayedItems = items

				val rowItems = items.map { item ->
					BaseItemDtoBaseRowItem(
						item,
						preferParentThumb,
						false
					)
				}
				replaceIfChanged(rowItems, BaseRowItem::itemSignature)

				if (items.isEmpty()) removeRow()
			} else {
				displayedItems = response.items

				val rowItems = response.items.map { item ->
					BaseItemDtoBaseRowItem(
						item,
						preferParentThumb,
						isStaticHeight
					)
				}
				replaceIfChanged(rowItems, BaseRowItem::itemSignature)

				if (response.items.isEmpty()) removeRow()
			}
		}.fold(
			onSuccess = {
				notifyRetrieveFinished()
				if (displayedItems.isNotEmpty()) {
					refreshCurrentStreamBadges(
						api = api,
						items = displayedItems,
						errorMessage = "Unable to refresh next up stream badges",
						shouldRefresh = BaseItemDto::needsDirectStreamBadgeSource,
					) { apiClient ->
						withDirectStreamBadges(apiClient)
					}
				}
			},
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

fun ItemRowAdapter.retrieveLatestMedia(api: ApiClient, query: GetLatestMediaRequest) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		var response = emptyList<BaseItemDto>()
		runCatching {
			response = withContext(Dispatchers.IO) {
				api.userLibraryApi.getLatestMedia(query).content
			}

			replaceLatestMediaItems(response)

			if (response.isEmpty()) removeRow()
		}.fold(
			onSuccess = {
				notifyRetrieveFinished()
				if (response.isNotEmpty()) refreshLatestStreamBadges(api, response)
			},
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

private fun ItemRowAdapter.refreshLatestStreamBadges(api: ApiClient, items: List<BaseItemDto>) {
	val badgeItems = items.streamBadgeItems(BaseItemDto::needsStreamBadgeSource)
	if (badgeItems.isEmpty()) return

	ProcessLifecycleOwner.get().lifecycleScope.launch {
		val enriched = try {
			withContext(Dispatchers.IO) {
				badgeItems.withLatestStreamBadges(api)
			}
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			Timber.w(error, "Unable to refresh latest media stream badges")
			return@launch
		}

		if (enriched != badgeItems) replaceBaseItemRows(enriched)
	}
}

private fun ItemRowAdapter.replaceLatestMediaItems(items: Collection<BaseItemDto>) {
	replaceAll(
		items = items.map { item -> latestMediaRowItem(item) },
		areItemsTheSame = { old, new -> (old as? BaseRowItem)?.itemId == (new as? BaseRowItem)?.itemId },
	)
	itemsLoaded = items.size
	totalItems = items.size
	addRowToParentIfResultsReceived()
}

private fun ItemRowAdapter.latestMediaRowItem(item: BaseItemDto) = BaseItemDtoBaseRowItem(
	item,
	preferParentThumb,
	isStaticHeight,
	BaseRowItemSelectAction.ShowDetails,
	preferParentThumb,
)

private suspend fun List<BaseItemDto>.withLatestStreamBadges(api: ApiClient): List<BaseItemDto> =
	withDirectStreamBadges(api).withSeriesOrSeasonStreamBadges(api)

private fun ItemRowAdapter.refreshCurrentStreamBadges(
	api: ApiClient,
	items: List<BaseItemDto>,
	errorMessage: String,
	shouldRefresh: (BaseItemDto) -> Boolean = BaseItemDto::needsStreamBadgeSource,
	enrich: suspend List<BaseItemDto>.(ApiClient) -> List<BaseItemDto>,
) {
	val badgeItems = items.streamBadgeItems(shouldRefresh)
	if (badgeItems.isEmpty()) return

	ProcessLifecycleOwner.get().lifecycleScope.launch {
		val enriched = try {
			withContext(Dispatchers.IO) {
				badgeItems.enrich(api)
			}
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			Timber.w(error, errorMessage)
			return@launch
		}

		if (enriched != badgeItems) replaceBaseItemRows(enriched)
	}
}

private fun Collection<BaseItemDto>.streamBadgeItems(shouldRefresh: (BaseItemDto) -> Boolean) =
	filter { item -> item.type in streamBadgeItemTypes && shouldRefresh(item) }

private fun BaseItemDto.needsStreamBadgeSource() =
	needsDirectStreamBadgeSource() || needsSeriesOrSeasonStreamBadgeSource()

private fun ItemRowAdapter.replaceBaseItemRows(items: List<BaseItemDto>) {
	val itemsById = items.associateBy { it.id }
	for (index in 0 until size()) {
		val oldItem = get(index) as? BaseItemDtoBaseRowItem ?: continue
		val currentItem = oldItem.baseItem ?: continue
		val itemId = oldItem.itemId ?: continue
		val mediaSources = itemsById[itemId]?.mediaSources ?: continue
		val streamBadgeMediaSources = mediaSources.takeUnless { it == currentItem.mediaSources }
		if (streamBadgeMediaSources == oldItem.streamBadgeMediaSources) continue

		set(
			index = index,
			element = oldItem.copyWithItem(
				item = currentItem,
				streamBadgeMediaSources = streamBadgeMediaSources,
			),
		)
	}
}

private suspend fun List<BaseItemDto>.withDirectStreamBadges(api: ApiClient): List<BaseItemDto> {
	val ids = asSequence()
		.filter { it.needsDirectStreamBadgeSource() }
		.map { it.id }
		.toList()
	if (ids.isEmpty()) return this

	val sourceItems = associateBy { it.id }
	val cachedItems = ids.mapNotNull { id ->
		DirectStreamBadgeCache.get(id)?.let { mediaSources ->
			val type = sourceItems[id]?.type ?: return@mapNotNull null
			id to BaseItemDto(
				id = id,
				type = type,
				mediaSources = mediaSources,
			)
		}
	}.toMap()
	val missingIds = ids.filterNot(cachedItems::containsKey)
	if (missingIds.isEmpty()) return withDirectStreamBadgeSources(cachedItems)

	val fetchedItems = try {
		api.itemsApi.getItems(GetItemsRequest(
			ids = missingIds,
			fields = STREAM_BADGE_FIELDS,
			enableImages = false,
			enableTotalRecordCount = false,
			enableUserData = false,
		)).content.items.associateBy { it.id }
	} catch (error: CancellationException) {
		throw error
	} catch (error: Exception) {
		Timber.w(error, "Unable to load stream badges for latest media")
		return withDirectStreamBadgeSources(cachedItems)
	}

	missingIds.forEach { id ->
		DirectStreamBadgeCache.save(id, fetchedItems[id]?.mediaSources.orEmpty())
	}

	return withDirectStreamBadgeSources(cachedItems + fetchedItems)
}

internal fun List<BaseItemDto>.withDirectStreamBadgeSources(items: Map<UUID, BaseItemDto>): List<BaseItemDto> = map { item ->
	val mediaSources = items[item.id]
		?.mediaSources
		?.takeIf { sources -> sources.any { it.hasBadgeStreams() } }

	if (item.needsDirectStreamBadgeSource() && mediaSources != null) {
		item.copy(mediaSources = mediaSources.withFallbackBadgeSources(item.mediaSources.orEmpty()))
	}
	else item
}

private fun List<MediaSourceInfo>.withFallbackBadgeSources(fallback: List<MediaSourceInfo>): List<MediaSourceInfo> {
	if (fallback.isEmpty()) return this

	val summary = badgeStreamSummary()
	val fallbackSummary = fallback.badgeStreamSummary()
	return if (summary.covers(fallbackSummary)) this else this + fallback.filterNot(::contains)
}

private fun BaseItemDto.needsDirectStreamBadgeSource() =
	type in DIRECT_STREAM_BADGE_TYPES && mediaSources?.hasCompleteRefreshBadgeStreams() != true

private fun MediaSourceInfo.hasBadgeStreams() =
	hasLanguageBadgeStreams() || hasVideoBadgeStreams()

private fun List<MediaSourceInfo>.hasCompleteRefreshBadgeStreams() =
	badgeStreamSummary(collectAll = false).isRefreshComplete

private fun List<MediaSourceInfo>.hasCacheableBadgeStreams() =
	any { it.hasBadgeStreams() }

private fun MediaSourceInfo.hasVideoBadgeStreams() =
	mediaStreams.orEmpty().any { it.type == MediaStreamType.VIDEO && it.hasVideoBadgeMetadata() }

private data class BadgeStreamSummary(
	val audioBadges: Set<String> = emptySet(),
	val subtitleBadges: Set<String> = emptySet(),
	val videoResolutions: Set<String> = emptySet(),
	val videoCodecs: Set<String> = emptySet(),
) {
	val isRefreshComplete get() =
		(audioBadges.isNotEmpty() || subtitleBadges.isNotEmpty()) &&
			videoResolutions.isNotEmpty() &&
			videoCodecs.isNotEmpty()

	fun covers(fallback: BadgeStreamSummary) =
		audioBadges.containsAll(fallback.audioBadges) &&
			subtitleBadges.containsAll(fallback.subtitleBadges) &&
			videoResolutions.containsAll(fallback.videoResolutions) &&
			videoCodecs.containsAll(fallback.videoCodecs)
}

private fun List<MediaSourceInfo>.badgeStreamSummary(collectAll: Boolean = true): BadgeStreamSummary {
	val audioBadges = mutableSetOf<String>()
	val subtitleBadges = mutableSetOf<String>()
	val videoResolutions = mutableSetOf<String>()
	val videoCodecs = mutableSetOf<String>()

	for (source in this) {
		for (stream in source.mediaStreams.orEmpty()) {
			when (stream.type) {
				MediaStreamType.AUDIO ->
					stream.languageBadgeText(MediaStreamType.AUDIO)?.let(audioBadges::add)

				MediaStreamType.SUBTITLE ->
					stream.languageBadgeText(MediaStreamType.SUBTITLE)?.let(subtitleBadges::add)

				MediaStreamType.VIDEO -> {
					stream.videoBadgeResolutionText()?.let(videoResolutions::add)
					stream.videoBadgeCodecText()?.let(videoCodecs::add)
				}

				else -> Unit
			}
		}

		val hasCompleteBadgeSummary = (audioBadges.isNotEmpty() || subtitleBadges.isNotEmpty()) &&
			videoResolutions.isNotEmpty() &&
			videoCodecs.isNotEmpty()
		if (!collectAll && hasCompleteBadgeSummary) break
	}

	return BadgeStreamSummary(
		audioBadges = audioBadges,
		subtitleBadges = subtitleBadges,
		videoResolutions = videoResolutions,
		videoCodecs = videoCodecs,
	)
}

private suspend fun List<BaseItemDto>.withSeriesOrSeasonStreamBadges(
	api: ApiClient,
): List<BaseItemDto> = coroutineScope {
	val seasonSamples = ConcurrentHashMap<UUID, Deferred<List<BaseItemDto>>>()
	val scope = this
	val sampleItems = asSequence()
		.filter { it.needsSeriesOrSeasonStreamBadgeSource() }
		.distinctBy { it.id }
		.toList()
	if (sampleItems.isEmpty()) return@coroutineScope this@withSeriesOrSeasonStreamBadges

	val parallelism = Semaphore(SERIES_STREAM_BADGE_PARALLELISM)
	val samples = sampleItems.map { item ->
		async {
			parallelism.withPermit {
				item.id to item.loadStreamBadgeSamples(api, seasonSamples, scope)
			}
		}
	}.awaitAll().toMap()
	withSeriesStreamBadgeSources(samples)
}

private suspend fun BaseItemDto.loadStreamBadgeSamples(
	api: ApiClient,
	seasonSamples: ConcurrentHashMap<UUID, Deferred<List<BaseItemDto>>>,
	scope: CoroutineScope,
): List<BaseItemDto> = try {
	when (type) {
		BaseItemKind.SERIES -> {
			val seasons = api.tvShowsApi.getSeasons(
				seriesId = id,
				fields = STREAM_BADGE_FIELDS,
				isMissing = false,
				enableImages = false,
				enableUserData = false,
			).content.items

			val sampledSeasonIds = seasons
				.filterNot { season -> season.mediaSources.orEmpty().hasCompleteRefreshBadgeStreams() }
				.spreadSeriesStreamBadgeSampleIds(SERIES_STREAM_BADGE_SEASON_SAMPLE_SIZE)
			val samples = mutableListOf<BaseItemDto>()
			for (season in seasons) {
				val seasonSample = if (season.mediaSources.orEmpty().hasCompleteRefreshBadgeStreams()) {
					season
				} else if (season.id !in sampledSeasonIds) {
					season.takeIf { it.mediaSources.orEmpty().hasCacheableBadgeStreams() } ?: continue
				} else {
					val episodes = seasonSamples.getOrLoad(season.id, scope) {
						api.loadCachedSeasonStreamBadgeSamples(id, season.id)
					}

					season.withSeriesStreamBadgeSource(episodes)
				}

				samples += seasonSample
			}
			samples
		}

		BaseItemKind.SEASON -> seriesId?.let { seriesId ->
			seasonSamples.getOrLoad(id, scope) {
				api.loadCachedSeasonStreamBadgeSamples(seriesId, id)
			}
		}

		else -> null
	}.orEmpty()
} catch (error: CancellationException) {
	throw error
} catch (error: Exception) {
	Timber.w(error, "Unable to load stream badge samples for $type $id")
	emptyList()
}

internal fun List<BaseItemDto>.spreadSeriesStreamBadgeSampleIds(limit: Int): Set<UUID> {
	if (limit <= 0) return emptySet()
	if (limit == 1) return firstOrNull()?.let { setOf(it.id) }.orEmpty()
	if (size <= limit) return map { it.id }.toSet()

	val lastIndex = lastIndex
	return (0 until limit)
		.map { sample -> get((sample * lastIndex.toFloat() / (limit - 1)).roundToInt()).id }
		.toSet()
}

private suspend fun ConcurrentHashMap<UUID, Deferred<List<BaseItemDto>>>.getOrLoad(
	seasonId: UUID,
	scope: CoroutineScope,
	load: suspend () -> List<BaseItemDto>,
): List<BaseItemDto> = computeIfAbsent(seasonId) {
	scope.async { load() }
}.await()

private suspend fun ApiClient.loadCachedSeasonStreamBadgeSamples(
	seriesId: UUID,
	seasonId: UUID,
): List<BaseItemDto> {
	SeriesStreamBadgeCache.get(seasonId)?.let { cached -> return cached }

	return loadSeasonStreamBadgeSamples(seriesId, seasonId).also { samples ->
		if (samples.isNotEmpty()) SeriesStreamBadgeCache.save(seriesId, seasonId, samples)
	}
}

private suspend fun ApiClient.loadSeasonStreamBadgeSamples(seriesId: UUID, seasonId: UUID): List<BaseItemDto> {
	var lastError: Exception? = null
	val samples = mutableListOf<BaseItemDto>()
	var remainingAttempts = SERIES_STREAM_BADGE_EPISODE_ATTEMPTS
	while (remainingAttempts-- > 0) {
		try {
			val startIndex = samples.size
			val items = tvShowsApi.getEpisodes(
				seriesId = seriesId,
				seasonId = seasonId,
				fields = STREAM_BADGE_FIELDS,
				isMissing = false,
				limit = if (samples.isEmpty()) SERIES_STREAM_BADGE_SAMPLE_SIZE else 1,
				startIndex = startIndex,
				sortBy = ItemSortBy.DATE_CREATED,
			).content.items
			if (items.isEmpty()) break

			samples += items
			if (samples.hasCompleteSeriesStreamBadgeSource(seasonId)) return samples
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			lastError = error
		}
	}

	if (samples.isNotEmpty()) return samples
	Timber.w(lastError, "Unable to load stream badge sample for season $seasonId")
	return emptyList()
}

private fun List<BaseItemDto>.hasCompleteSeriesStreamBadgeSource(seasonId: UUID) =
	BaseItemDto(id = seasonId, type = BaseItemKind.SEASON)
		.withSeriesStreamBadgeSource(this)
		.mediaSources
		.orEmpty()
		.hasCompleteRefreshBadgeStreams()

internal fun List<BaseItemDto>.withSeriesStreamBadgeSources(
	samples: Map<UUID, List<BaseItemDto>>,
): List<BaseItemDto> = map { item ->
	if (item.type == BaseItemKind.SERIES || item.type == BaseItemKind.SEASON) {
		item.withSeriesStreamBadgeSource(samples[item.id].orEmpty())
	} else {
		item
	}
}

private fun BaseItemDto.needsSeriesOrSeasonStreamBadgeSource() =
	(type == BaseItemKind.SERIES || type == BaseItemKind.SEASON) &&
		mediaSources?.hasCompleteRefreshBadgeStreams() != true

internal fun BaseItemDto.withSeriesStreamBadgeSource(episodes: List<BaseItemDto>): BaseItemDto {
	val sources = episodes
		.asSequence()
		.flatMap { episode -> episode.mediaSources.orEmpty().asSequence() }
		.filter { it.hasBadgeStreams() }
		.toList()
	val source = sources.firstOrNull() ?: return this
	val badgeSources = sources.withFallbackBadgeSources(mediaSources.orEmpty())
	val audio = badgeSources.aggregateStreams(MediaStreamType.AUDIO)
	val subtitle = badgeSources.aggregateStreams(MediaStreamType.SUBTITLE)
	val video = badgeSources.aggregateVideoStreams()
	val streams = video + audio.streams + subtitle.streams
	if (streams.isEmpty()) return this

	return copy(
		mediaSources = listOf(source.copy(
			mediaStreams = streams,
			defaultAudioStreamIndex = audio.defaultIndex,
			defaultSubtitleStreamIndex = subtitle.defaultIndex ?: -1,
		))
	)
}

private data class AggregateStreams(
	val streams: List<MediaStream>,
	val defaultIndex: Int?,
)

private fun List<MediaSourceInfo>.aggregateStreams(type: MediaStreamType): AggregateStreams {
	val representative = representativeStream(type)
	val representativeBadge = representative?.languageBadgeText(type)
	val streams = asSequence()
		.flatMap { it.mediaStreams.orEmpty().asSequence() }
		.mapNotNull { stream -> stream.languageBadgeText(type)?.let { badge -> stream to badge } }
		.distinctBy { (_, badge) -> badge }
		.sortedByDescending { (_, badge) -> representativeBadge != null && badge == representativeBadge }
		.mapIndexed { index, (stream, badge) ->
			stream.copy(
				index = index,
				isDefault = representativeBadge != null && badge == representativeBadge,
			)
		}
		.toList()

	return AggregateStreams(streams, streams.firstOrNull { it.isDefault }?.index)
}

private fun List<MediaSourceInfo>.aggregateVideoStreams(): List<MediaStream> = asSequence()
	.flatMap { it.mediaStreams.orEmpty().asSequence() }
	.filter { it.type == MediaStreamType.VIDEO }
	.filter { it.hasVideoBadgeMetadata() }
	.distinctBy { stream -> listOf(stream.videoBadgeResolutionText(), stream.videoBadgeCodecText()) }
	.mapIndexed { index, stream -> stream.copy(index = index) }
	.toList()

private fun List<MediaSourceInfo>.representativeStream(type: MediaStreamType): MediaStream? {
	val streams = mapNotNull { it.selectedStream(type) }
	val counts = streams.groupingBy { it.languageBadgeText(type) }.eachCount()
	return streams.maxByOrNull { stream -> counts[stream.languageBadgeText(type)] ?: 0 }
}

private fun MediaSourceInfo.selectedStream(type: MediaStreamType): MediaStream? {
	val streams = mediaStreams.orEmpty().filter { stream -> stream.hasLanguageBadge(type) }
	if (type == MediaStreamType.SUBTITLE && defaultSubtitleStreamIndex == -1) return null

	val defaultIndex = when (type) {
		MediaStreamType.AUDIO -> defaultAudioStreamIndex
		MediaStreamType.SUBTITLE -> defaultSubtitleStreamIndex
		else -> null
	}

	return defaultIndex?.let { index -> streams.firstOrNull { it.index == index } }
		?: streams.firstOrNull { it.isDefault }
		?: streams.firstOrNull()
}

internal object SeriesStreamBadgeCache {
	private const val SHARED_PREFERENCES_NAME = "series_stream_badges_v1"
	private val CACHE_TTL_MS = TimeUnit.DAYS.toMillis(7)

	private val json = Json {
		encodeDefaults = true
		ignoreUnknownKeys = true
	}
	private val badges = ConcurrentHashMap<UUID, CachedSeasonBadge>()

	@Volatile
	private var store: Store? = null

	@Serializable
	private data class CachedSeasonBadge(
		val createdAtMillis: Long,
		val seriesId: String? = null,
		val sampleIds: List<String> = emptyList(),
		val mediaSources: List<MediaSourceInfo> = emptyList(),
	)

	fun initialize(context: Context) {
		synchronized(this) {
			if (store != null) return

			store = Store(context.applicationContext).also { badgeStore ->
				badges.putAll(badgeStore.load(System.currentTimeMillis()))
			}
		}
	}

	fun get(seasonId: UUID): List<BaseItemDto>? {
		val now = System.currentTimeMillis()
		val cached = badges[seasonId] ?: return null
		if (cached.isExpired(now) || !cached.mediaSources.hasCacheableBadgeStreams()) {
			badges.remove(seasonId)
			store?.remove(seasonId)
			return null
		}

		return listOf(BaseItemDto(
			id = seasonId,
			type = BaseItemKind.SEASON,
			mediaSources = cached.mediaSources,
		))
	}

	fun save(seriesId: UUID, seasonId: UUID, samples: List<BaseItemDto>) {
		val mediaSources = BaseItemDto(id = seasonId, type = BaseItemKind.SEASON)
			.withSeriesStreamBadgeSource(samples)
			.mediaSources
			.orEmpty()
		if (!mediaSources.hasCacheableBadgeStreams()) return

		val cached = CachedSeasonBadge(
			createdAtMillis = System.currentTimeMillis(),
			seriesId = seriesId.toString(),
			sampleIds = samples.map { sample -> sample.id.toString() },
			mediaSources = mediaSources,
		)
		badges[seasonId] = cached
		store?.save(seasonId, cached)
	}

	fun remove(itemIds: Set<UUID>) {
		if (itemIds.isEmpty()) return

		val itemIdStrings = itemIds.map(UUID::toString).toSet()
		val seasonIds = badges
			.filter { (seasonId, badge) ->
				seasonId in itemIds ||
					badge.seriesId in itemIdStrings ||
					badge.sampleIds.any { sampleId -> sampleId in itemIdStrings }
			}
			.keys
		if (seasonIds.isEmpty()) return

		seasonIds.forEach(badges::remove)
		store?.remove(seasonIds)
	}

	fun clear() {
		badges.clear()
		store?.clear()
	}

	private fun CachedSeasonBadge.isExpired(now: Long) =
		now - createdAtMillis > CACHE_TTL_MS

	private class Store(context: Context) {
		private val preferences = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)

		fun load(now: Long): Map<UUID, CachedSeasonBadge> {
			val staleKeys = mutableListOf<String>()
			val cached = preferences.all.mapNotNull { (seasonId, value) ->
				val id = runCatching { UUID.fromString(seasonId) }.getOrNull()
				if (id == null) {
					staleKeys.add(seasonId)
					return@mapNotNull null
				}

				val badge = (value as? String)
					?.let { storedValue -> runCatching { json.decodeFromString<CachedSeasonBadge>(storedValue) }.getOrNull() }
					?.takeIf { cachedBadge ->
						cachedBadge.seriesId != null &&
							cachedBadge.mediaSources.hasCacheableBadgeStreams() &&
							!cachedBadge.isExpired(now)
					}

				if (badge == null) {
					staleKeys.add(seasonId)
					null
				} else {
					id to badge
				}
			}.toMap()

			if (staleKeys.isNotEmpty()) {
				preferences.edit().apply {
					staleKeys.forEach(::remove)
					apply()
				}
			}

			return cached
		}

		fun save(seasonId: UUID, badge: CachedSeasonBadge) {
			preferences.edit()
				.putString(seasonId.toString(), json.encodeToString(badge))
				.apply()
		}

		fun remove(seasonId: UUID) {
			preferences.edit()
				.remove(seasonId.toString())
				.apply()
		}

		fun remove(seasonIds: Collection<UUID>) {
			preferences.edit()
				.apply {
					seasonIds.forEach { seasonId -> remove(seasonId.toString()) }
					apply()
				}
		}

		fun clear() {
			preferences.edit()
				.clear()
				.apply()
		}
	}
}

internal object DirectStreamBadgeCache {
	private val CACHE_TTL_MS = TimeUnit.DAYS.toMillis(7)
	private val EMPTY_CACHE_TTL_MS = TimeUnit.HOURS.toMillis(1)
	private val badges = ConcurrentHashMap<UUID, CachedDirectBadge>()

	private data class CachedDirectBadge(
		val createdAtMillis: Long,
		val mediaSources: List<MediaSourceInfo>,
	)

	fun get(itemId: UUID): List<MediaSourceInfo>? {
		val now = System.currentTimeMillis()
		val cached = badges[itemId] ?: return null
		if (cached.isExpired(now)) {
			badges.remove(itemId)
			return null
		}

		return cached.mediaSources
	}

	fun save(itemId: UUID, mediaSources: List<MediaSourceInfo>) {
		badges[itemId] = CachedDirectBadge(
			createdAtMillis = System.currentTimeMillis(),
			mediaSources = mediaSources,
		)
	}

	fun remove(itemIds: Set<UUID>) {
		itemIds.forEach(badges::remove)
	}

	fun clear() {
		badges.clear()
	}

	private fun CachedDirectBadge.isExpired(now: Long) =
		now - createdAtMillis > if (mediaSources.isEmpty()) EMPTY_CACHE_TTL_MS else CACHE_TTL_MS
}

private val DIRECT_STREAM_BADGE_TYPES = streamBadgeItemTypes - setOf(BaseItemKind.SERIES, BaseItemKind.SEASON)
private val STREAM_BADGE_FIELDS = setOf(ItemFields.MEDIA_SOURCES, ItemFields.MEDIA_STREAMS)
private const val SERIES_STREAM_BADGE_EPISODE_ATTEMPTS = 3
private const val SERIES_STREAM_BADGE_SAMPLE_SIZE = 2
private const val SERIES_STREAM_BADGE_SEASON_SAMPLE_SIZE = 6
private const val SERIES_STREAM_BADGE_PARALLELISM = 8

fun ItemRowAdapter.retrieveSpecialFeatures(api: ApiClient, query: GetSpecialsRequest) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.userLibraryApi.getSpecialFeatures(query.itemId).content
			}

			setItems(
				items = response,
				transform = { item, _ ->
					BaseItemDtoBaseRowItem(item, preferParentThumb, false)
				}
			)

			if (response.isEmpty()) removeRow()
		}.fold(
			onSuccess = { notifyRetrieveFinished() },
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

fun ItemRowAdapter.retrieveAdditionalParts(api: ApiClient, query: GetAdditionalPartsRequest) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.videosApi.getAdditionalPart(query.itemId).content
			}

			setItems(
				items = response.items,
				transform = { item, _ -> BaseItemDtoBaseRowItem(item) }
			)

			if (response.items.isEmpty()) removeRow()
		}.fold(
			onSuccess = { notifyRetrieveFinished() },
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

fun ItemRowAdapter.retrieveUserViews(api: ApiClient, userViewsRepository: UserViewsRepository) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.userViewsApi.getUserViews().content
			}

			val filteredItems = userViewsRepository.withSpecialViews(response.items)

			setItems(
				items = filteredItems,
				transform = { item, _ -> BaseItemDtoBaseRowItem(item, staticHeight = true) }
			)

			if (filteredItems.isEmpty()) removeRow()
		}.fold(
			onSuccess = { notifyRetrieveFinished() },
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

fun ItemRowAdapter.retrieveSeasons(api: ApiClient, query: GetSeasonsRequest) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		var items = emptyList<BaseItemDto>()
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.tvShowsApi.getSeasons(query).content
			}
			items = response.items

			setItems(
				items = response.items,
				transform = { item, _ -> BaseItemDtoBaseRowItem(item) }
			)

			if (response.items.isEmpty()) removeRow()
		}.fold(
			onSuccess = {
				notifyRetrieveFinished()
				if (items.isNotEmpty()) {
					refreshCurrentStreamBadges(
						api = api,
						items = items,
						errorMessage = "Unable to refresh season stream badges",
						shouldRefresh = BaseItemDto::needsSeriesOrSeasonStreamBadgeSource,
					) { apiClient ->
						withSeriesOrSeasonStreamBadges(apiClient)
					}
				}
			},
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

fun ItemRowAdapter.retrieveUpcomingEpisodes(api: ApiClient, query: GetUpcomingEpisodesRequest) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.tvShowsApi.getUpcomingEpisodes(query).content
			}

			setItems(
				items = response.items,
				transform = { item, _ -> BaseItemDtoBaseRowItem(item) }
			)

			if (response.items.isEmpty()) removeRow()
		}.fold(
			onSuccess = { notifyRetrieveFinished() },
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

fun ItemRowAdapter.retrieveSimilarItems(api: ApiClient, query: GetSimilarItemsRequest) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.libraryApi.getSimilarItems(query).content
			}

			setItems(
				items = response.items,
				transform = { item, _ -> BaseItemDtoBaseRowItem(item) }
			)

			if (response.items.isEmpty()) removeRow()
		}.fold(
			onSuccess = { notifyRetrieveFinished() },
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

fun ItemRowAdapter.retrieveTrailers(api: ApiClient, query: GetTrailersRequest) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.userLibraryApi.getLocalTrailers(itemId = query.itemId)
			}.content

			setItems(
				items = response,
				transform = { item, _ ->
					BaseItemDtoBaseRowItem(
						item,
						preferParentThumb,
						false,
						BaseRowItemSelectAction.Play,
						false
					)
				}
			)

			if (response.isEmpty()) removeRow()
		}.fold(
			onSuccess = { notifyRetrieveFinished() },
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

fun ItemRowAdapter.retrieveLiveTvRecommendedPrograms(
	api: ApiClient,
	query: GetRecommendedProgramsRequest,
	selectAction: BaseRowItemSelectAction = BaseRowItemSelectAction.ShowDetails,
) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		runCatching {
			val items = withContext(Dispatchers.IO) {
				val programs = api.liveTvApi.getRecommendedPrograms(query).content.items
				if (programs.isEmpty() || programs.all { program -> program.channelPrimaryImageTag != null }) {
					return@withContext programs
				}

				val channels = api.liveTvApi.getLiveTvChannels(
					GetLiveTvChannelsRequest(
						addCurrentProgram = false,
						limit = LIVE_TV_CHANNEL_IMAGE_FALLBACK_LIMIT,
					)
				).content.items
					.associateBy { channel -> channel.id }

				programs.map { program -> program.withChannelImage(channels[program.channelId]) }
			}

			val rowItems = items.map { item ->
				BaseItemDtoBaseRowItem(
					item,
					false,
					isStaticHeight,
					selectAction,
				)
			}
			replaceIfChanged(rowItems, BaseRowItem::liveTvProgramSignature)
			prefetchLiveTvTracks(api, items)

			if (items.isEmpty()) removeRow()
		}.fold(
			onSuccess = { notifyRetrieveFinished() },
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

private fun BaseItemDto.withChannelImage(channel: BaseItemDto?) = copy(
	channelName = channelName ?: channel?.name,
	channelNumber = channelNumber ?: channel?.number,
	channelPrimaryImageTag = channelPrimaryImageTag ?: channel?.imageTags?.get(ImageType.PRIMARY),
)

private fun ItemRowAdapter.prefetchLiveTvTracks(
	api: ApiClient,
	items: Collection<BaseItemDto>,
) {
	LiveTvTrackCache.prefetchMissingOnce(api, items) { channelId ->
		refreshLiveTvTrackBadge(channelId)
	}
}

private fun ItemRowAdapter.refreshLiveTvTrackBadge(channelId: UUID) {
	for (index in 0 until size()) {
		val rowItem = get(index) as? BaseItemDtoBaseRowItem ?: continue
		if (rowItem.baseItem?.liveTvChannelId() == channelId) {
			set(index, rowItem)
		}
	}
}

fun ItemRowAdapter.retrieveLiveTvRecordings(api: ApiClient, query: GetRecordingsRequest) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.liveTvApi.getRecordings(query).content
			}

			setItems(
				items = response.items,
				transform = { item, _ ->
					BaseItemDtoBaseRowItem(
						item,
						false,
						isStaticHeight,
					)
				}
			)

			if (response.items.isEmpty()) removeRow()
		}.fold(
			onSuccess = { notifyRetrieveFinished() },
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

fun ItemRowAdapter.retrieveLiveTvSeriesTimers(
	api: ApiClient,
	context: Context,
	canManageRecordings: Boolean
) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.liveTvApi.getSeriesTimers().content
			}

			setItems(
				items = buildList {
					add(
						GridButton(
							LiveTvOption.LIVE_TV_CHANNELS_OPTION_ID,
							context.getString(R.string.channels)
						)
					)

					add(
						GridButton(
							LiveTvOption.LIVE_TV_RECORDINGS_OPTION_ID,
							context.getString(R.string.lbl_recorded_tv)
						)
					)

					if (canManageRecordings) {
						add(
							GridButton(
								LiveTvOption.LIVE_TV_SCHEDULE_OPTION_ID,
								context.getString(R.string.lbl_schedule)
							)
						)

						add(
							GridButton(
								LiveTvOption.LIVE_TV_SERIES_OPTION_ID,
								context.getString(R.string.lbl_series)
							)
						)
					}

					addAll(response.items)
				},
				transform = { item, _ ->
					when (item) {
						is GridButton -> GridButtonBaseRowItem(item)
						is SeriesTimerInfoDto -> SeriesTimerInfoDtoBaseRowItem(item)
						else -> error("Unknown type for item")
					}
				}
			)

			if (response.items.isEmpty()) removeRow()
		}.fold(
			onSuccess = { notifyRetrieveFinished() },
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

fun ItemRowAdapter.retrieveLiveTvChannels(
	api: ApiClient,
	query: GetLiveTvChannelsRequest,
	startIndex: Int,
	batchSize: Int
) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.liveTvApi.getLiveTvChannels(
					query.copy(
						startIndex = startIndex,
						limit = batchSize,
					)
				).content
			}

			val filterToCurrentWeek = query.isRecentlyPlayedChannelsRequest()
			val items = if (filterToCurrentWeek) {
				val weekStart = currentWeekStart()
				response.items.filter { item -> item.wasPlayedSince(weekStart) }
			} else {
				response.items
			}
			val reachedOlderItems = filterToCurrentWeek && items.size < response.items.size

			totalItems = if (reachedOlderItems) startIndex + items.size else response.totalRecordCount
			setItems(
				items = items,
				transform = { item, _ ->
					BaseItemDtoBaseRowItem(
						item,
						false,
						isStaticHeight,
					)
				},
			)
			prefetchLiveTvTracks(api, items)

			if (itemsLoaded == 0) removeRow()
		}.fold(
			onSuccess = { notifyRetrieveFinished() },
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

private fun GetLiveTvChannelsRequest.isRecentlyPlayedChannelsRequest() =
	sortBy?.contains(ItemSortBy.DATE_PLAYED) == true && isFavorite == null

private fun currentWeekStart(): LocalDateTime {
	val firstDayOfWeek = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
	return LocalDate.now()
		.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
		.atStartOfDay()
}

private fun BaseItemDto.wasPlayedSince(startDate: LocalDateTime) =
	userData?.lastPlayedDate?.let { lastPlayedDate -> !lastPlayedDate.isBefore(startDate) } == true

fun ItemRowAdapter.retrieveAlbumArtists(
	api: ApiClient,
	query: GetAlbumArtistsRequest,
	startIndex: Int,
	batchSize: Int
) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.artistsApi.getAlbumArtists(
					query.copy(
						startIndex = startIndex,
						limit = batchSize,
					)
				).content
			}

			totalItems = response.totalRecordCount
			setItems(
				items = response.items,
				transform = { item, _ ->
					BaseItemDtoBaseRowItem(
						item,
						preferParentThumb,
						isStaticHeight,
					)
				},
			)

			if (response.items.isEmpty()) removeRow()
		}.fold(
			onSuccess = { notifyRetrieveFinished() },
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

fun ItemRowAdapter.retrieveArtists(
	api: ApiClient,
	query: GetArtistsRequest,
	startIndex: Int,
	batchSize: Int
) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.artistsApi.getArtists(
					query.copy(
						startIndex = startIndex,
						limit = batchSize,
					)
				).content
			}

			totalItems = response.totalRecordCount
			setItems(
				items = response.items,
				transform = { item, _ ->
					BaseItemDtoBaseRowItem(
						item,
						preferParentThumb,
						isStaticHeight,
					)
				},
			)

			if (response.items.isEmpty()) removeRow()
		}.fold(
			onSuccess = { notifyRetrieveFinished() },
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

fun ItemRowAdapter.retrieveItems(
	api: ApiClient,
	query: GetItemsRequest,
	startIndex: Int,
	batchSize: Int
) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		var streamBadgeItems = emptyList<BaseItemDto>()
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.itemsApi.getItems(
					query.copy(
						startIndex = startIndex,
						limit = batchSize,
					)
				).content
			}

			totalItems = response.totalRecordCount
			val initialSelectedPosition = getPendingInitialSelectedPosition()
			if (initialSelectedPosition != null && totalItems <= initialSelectedPosition) {
				removeRow()
				return@runCatching
			}
			streamBadgeItems = response.items
			val showRemainingTimeBadge = query.showsRemainingTimeBadges()

			setItems(
				items = response.items,
				transform = { item, _ ->
					item.toBaseItemRowItem(
						preferParentThumb = preferParentThumb,
						staticHeight = isStaticHeight,
						showRemainingTimeBadge = showRemainingTimeBadge,
					)
				},
			)

			if (itemsLoaded == 0) removeRow()
		}.fold(
			onSuccess = {
				notifyRetrieveFinished()
				if (streamBadgeItems.isNotEmpty()) {
					refreshCurrentStreamBadges(api, streamBadgeItems, "Unable to refresh item stream badges") { apiClient ->
						withLatestStreamBadges(apiClient)
					}
				}
			},
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

fun ItemRowAdapter.retrievePremieres(
	api: ApiClient,
	query: GetItemsRequest,
) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		runCatching {
			val response = withContext(Dispatchers.IO) {
				api.itemsApi.getItems(query).content
			}

			setItems(
				items = response.items,
				transform = { item, _ ->
					BaseItemDtoBaseRowItem(
						item,
						preferParentThumb,
						isStaticHeight,
					)
				}
			)

			if (response.items.isEmpty()) removeRow()
		}.fold(
			onSuccess = { notifyRetrieveFinished() },
			onFailure = { error -> notifyRetrieveFinished(error as? Exception) }
		)
	}
}

// Request modifiers

fun setAlbumArtistsSorting(
	request: GetAlbumArtistsRequest,
	sortOption: SortOption,
) = request.copy(
	sortBy = setOf(sortOption.value, ItemSortBy.SORT_NAME),
	sortOrder = setOf(sortOption.order)
)

fun setArtistsSorting(
	request: GetArtistsRequest,
	sortOption: SortOption,
) = request.copy(
	sortBy = setOf(sortOption.value, ItemSortBy.SORT_NAME),
	sortOrder = setOf(sortOption.order)
)

fun setItemsSorting(
	request: GetItemsRequest,
	sortOption: SortOption,
) = request.copy(
	sortBy = setOf(sortOption.value, ItemSortBy.SORT_NAME),
	sortOrder = setOf(sortOption.order)
)

fun setAlbumArtistsFilter(
	request: GetAlbumArtistsRequest,
	filters: Collection<ItemFilter>?,
) = request.copy(
	filters = filters,
)

fun setArtistsFilter(
	request: GetArtistsRequest,
	filters: Collection<ItemFilter>?,
) = request.copy(
	filters = filters,
)

fun setItemsFilter(
	request: GetItemsRequest,
	filters: Collection<ItemFilter>?,
) = request.copy(
	filters = filters,
)

fun setAlbumArtistsStartLetter(
	request: GetAlbumArtistsRequest,
	startLetter: String?,
) = request.copy(
	nameStartsWith = startLetter,
)

fun setArtistsStartLetter(
	request: GetArtistsRequest,
	startLetter: String?,
) = request.copy(
	nameStartsWith = startLetter,
)

fun setItemsStartLetter(
	request: GetItemsRequest,
	startLetter: String?,
) = request.copy(
	nameStartsWith = startLetter,
)

@JvmOverloads
fun ItemRowAdapter.refreshItem(
	api: ApiClient,
	lifecycleOwner: LifecycleOwner,
	currentBaseRowItem: BaseRowItem,
	callback: () -> Unit = {}
) {
	if (currentBaseRowItem !is BaseItemDtoBaseRowItem || currentBaseRowItem is AudioQueueBaseRowItem) return
	val currentBaseItem = currentBaseRowItem.baseItem ?: return

	lifecycleOwner.lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.userLibraryApi.getItem(itemId = currentBaseItem.id).content
			}
		}.fold(
			onSuccess = { refreshedBaseItem ->
				val index = indexOf(currentBaseRowItem)
				// Item could be removed while API was loading, check if the index is valid first
				if (index == -1) return@fold

				set(
					index = index,
					element = currentBaseRowItem.copyWithItem(refreshedBaseItem)
				)
			},
			onFailure = { err ->
				if (err is InvalidStatusException && err.status == 404) remove(currentBaseRowItem)
				else Timber.e(err, "Failed to refresh item")
			}
		)

		callback()
	}
}
