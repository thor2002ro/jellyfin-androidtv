package org.jellyfin.androidtv.ui.itemhandling

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
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

	replaceAll(allItems)
	itemsLoaded = allItems.size
	addRowToParentIfResultsReceived()
}

private fun BaseRowItem.resumeSignature() = listOf(
	itemId,
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
				BaseItemDtoBaseRowItem(
					it,
					preferParentThumb,
					isStaticHeight
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
					refreshCurrentStreamBadges(api, displayedItems, "Unable to refresh next up stream badges") { apiClient ->
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
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		val enriched = try {
			withContext(Dispatchers.IO) {
				items.withLatestStreamBadges(api)
			}
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			Timber.w(error, "Unable to refresh latest media stream badges")
			return@launch
		}

		if (enriched != items) replaceBaseItemRows(enriched)
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
	enrich: suspend List<BaseItemDto>.(ApiClient) -> List<BaseItemDto>,
) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		val enriched = try {
			withContext(Dispatchers.IO) {
				items.enrich(api)
			}
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			Timber.w(error, errorMessage)
			return@launch
		}

		if (enriched != items) replaceBaseItemRows(enriched)
	}
}

private fun ItemRowAdapter.replaceBaseItemRows(items: List<BaseItemDto>) {
	val itemsById = items.associateBy { it.id }
	for (index in 0 until size()) {
		val oldItem = get(index) as? BaseItemDtoBaseRowItem ?: continue
		val currentItem = oldItem.baseItem ?: continue
		val itemId = oldItem.itemId ?: continue
		val mediaSources = itemsById[itemId]?.mediaSources ?: continue
		if (mediaSources == oldItem.streamBadgeMediaSources || mediaSources == currentItem.mediaSources) continue

		set(
			index = index,
			element = BaseItemDtoBaseRowItem(
				item = currentItem,
				preferParentThumb = oldItem.preferParentThumb,
				staticHeight = oldItem.staticHeight,
				selectAction = oldItem.selectAction,
				preferSeriesPoster = oldItem.preferSeriesPoster,
				streamBadgeMediaSources = mediaSources,
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

	val items = try {
		api.itemsApi.getItems(GetItemsRequest(
			ids = ids,
			fields = STREAM_BADGE_FIELDS,
			enableImages = false,
			enableTotalRecordCount = false,
			enableUserData = false,
		)).content.items.associateBy { it.id }
	} catch (error: CancellationException) {
		throw error
	} catch (error: Exception) {
		Timber.w(error, "Unable to load stream badges for latest media")
		emptyMap()
	}

	return withDirectStreamBadgeSources(items)
}

internal fun List<BaseItemDto>.withDirectStreamBadgeSources(items: Map<UUID, BaseItemDto>): List<BaseItemDto> = map { item ->
	val mediaSources = items[item.id]
		?.mediaSources
		?.takeIf { sources -> sources.any { it.hasBadgeStreams() } }

	if (item.needsDirectStreamBadgeSource() && mediaSources != null) item.copy(mediaSources = mediaSources)
	else item
}

private fun BaseItemDto.needsDirectStreamBadgeSource() =
	type in DIRECT_STREAM_BADGE_TYPES && mediaSources?.any { it.hasBadgeStreams() } != true

private fun MediaSourceInfo.hasBadgeStreams() =
	mediaStreams.orEmpty().any { it.type == MediaStreamType.AUDIO || it.type == MediaStreamType.SUBTITLE }

private suspend fun List<BaseItemDto>.withSeriesOrSeasonStreamBadges(
	api: ApiClient,
): List<BaseItemDto> = coroutineScope {
	val seasonSamples = ConcurrentHashMap<UUID, List<BaseItemDto>>()
	val sampleItems = asSequence()
		.filter { it.needsSeriesOrSeasonStreamBadgeSource() }
		.distinctBy { it.id }
		.toList()
	if (sampleItems.isEmpty()) return@coroutineScope this@withSeriesOrSeasonStreamBadges

	val parallelism = Semaphore(SERIES_STREAM_BADGE_PARALLELISM)
	val samples = sampleItems.map { item ->
		async {
			parallelism.withPermit {
				item.id to item.loadStreamBadgeSamples(api, seasonSamples)
			}
		}
	}.awaitAll().toMap()
	withSeriesStreamBadgeSources(samples)
}

private suspend fun BaseItemDto.loadStreamBadgeSamples(
	api: ApiClient,
	seasonSamples: MutableMap<UUID, List<BaseItemDto>>,
): List<BaseItemDto> = try {
	when (type) {
		BaseItemKind.SERIES -> api.tvShowsApi.getSeasons(
			seriesId = id,
			fields = STREAM_BADGE_FIELDS,
			isMissing = false,
			enableImages = false,
			enableUserData = false,
		).content.items.map { season ->
			val episodes = seasonSamples[season.id]
				?: api.loadCachedSeasonStreamBadgeSamples(id, season.id, seasonSamples)

			season.withSeriesStreamBadgeSource(episodes)
		}

		BaseItemKind.SEASON -> seriesId?.let { seriesId ->
			seasonSamples[id]
				?: api.loadCachedSeasonStreamBadgeSamples(seriesId, id, seasonSamples)
		}

		else -> null
	}.orEmpty()
} catch (error: CancellationException) {
	throw error
} catch (error: Exception) {
	Timber.w(error, "Unable to load stream badge samples for $type $id")
	emptyList()
}

private suspend fun ApiClient.loadCachedSeasonStreamBadgeSamples(
	seriesId: UUID,
	seasonId: UUID,
	seasonSamples: MutableMap<UUID, List<BaseItemDto>>,
): List<BaseItemDto> {
	SeriesStreamBadgeCache.get(seasonId)?.let { cached ->
		seasonSamples[seasonId] = cached
		return cached
	}

	return loadSeasonStreamBadgeSamples(seriesId, seasonId).also { samples ->
		seasonSamples[seasonId] = samples
		if (samples.isNotEmpty()) SeriesStreamBadgeCache.save(seriesId, seasonId, samples)
	}
}

private suspend fun ApiClient.loadSeasonStreamBadgeSamples(seriesId: UUID, seasonId: UUID): List<BaseItemDto> {
	var lastError: Exception? = null
	for (startIndex in 0 until SERIES_STREAM_BADGE_EPISODE_ATTEMPTS) {
		try {
			return tvShowsApi.getEpisodes(
				seriesId = seriesId,
				seasonId = seasonId,
				fields = STREAM_BADGE_FIELDS,
				isMissing = false,
				limit = if (startIndex == 0) SERIES_STREAM_BADGE_SAMPLE_SIZE else 1,
				startIndex = startIndex,
				sortBy = ItemSortBy.DATE_CREATED,
			).content.items
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			lastError = error
		}
	}

	Timber.w(lastError, "Unable to load stream badge sample for season $seasonId")
	return emptyList()
}

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
		mediaSources?.any { it.hasBadgeStreams() } != true

internal fun BaseItemDto.withSeriesStreamBadgeSource(episodes: List<BaseItemDto>): BaseItemDto {
	val sources = episodes
		.asSequence()
		.mapNotNull { episode -> episode.mediaSources?.firstOrNull { it.hasBadgeStreams() } }
		.toList()
	val source = sources.firstOrNull() ?: return this
	val audio = sources.aggregateStreams(MediaStreamType.AUDIO)
	val subtitle = sources.aggregateStreams(MediaStreamType.SUBTITLE)

	return copy(
		mediaSources = listOf(source.copy(
			mediaStreams = audio.streams + subtitle.streams,
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
	val streams = asSequence()
		.flatMap { it.mediaStreams.orEmpty().asSequence() }
		.filter { it.type == type }
		.distinctBy { it.language?.lowercase(Locale.ROOT) }
		.sortedByDescending { stream -> representative != null && stream.language.equals(representative.language, ignoreCase = true) }
		.mapIndexed { index, stream ->
			stream.copy(
				index = index,
				isDefault = representative != null && stream.language.equals(representative.language, ignoreCase = true),
			)
		}
		.toList()

	return AggregateStreams(streams, streams.firstOrNull { it.isDefault }?.index)
}

private fun List<MediaSourceInfo>.representativeStream(type: MediaStreamType): MediaStream? {
	val streams = mapNotNull { it.selectedStream(type) }
	return streams.maxByOrNull { stream -> streams.count { it.language.equals(stream.language, ignoreCase = true) } }
}

private fun MediaSourceInfo.selectedStream(type: MediaStreamType): MediaStream? {
	val streams = mediaStreams.orEmpty()
	if (type == MediaStreamType.SUBTITLE && defaultSubtitleStreamIndex == -1) return null

	val defaultIndex = when (type) {
		MediaStreamType.AUDIO -> defaultAudioStreamIndex
		MediaStreamType.SUBTITLE -> defaultSubtitleStreamIndex
		else -> null
	}

	return defaultIndex?.let { index -> streams.firstOrNull { it.type == type && it.index == index } }
		?: streams.firstOrNull { it.type == type && it.isDefault }
		?: streams.firstOrNull { it.type == type }
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
		if (cached.isExpired(now)) {
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
		if (mediaSources.isEmpty()) return

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
					?.takeIf { cachedBadge -> cachedBadge.seriesId != null && cachedBadge.mediaSources.isNotEmpty() && !cachedBadge.isExpired(now) }

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

private val DIRECT_STREAM_BADGE_TYPES = setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO)
private val STREAM_BADGE_FIELDS = setOf(ItemFields.MEDIA_SOURCES, ItemFields.MEDIA_STREAMS)
private const val SERIES_STREAM_BADGE_EPISODE_ATTEMPTS = 3
private const val SERIES_STREAM_BADGE_SAMPLE_SIZE = 2
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
					refreshCurrentStreamBadges(api, items, "Unable to refresh season stream badges") { apiClient ->
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
					element = BaseItemDtoBaseRowItem(
						item = refreshedBaseItem,
						preferParentThumb = currentBaseRowItem.preferParentThumb,
						staticHeight = currentBaseRowItem.staticHeight,
						selectAction = currentBaseRowItem.selectAction,
						preferSeriesPoster = currentBaseRowItem.preferSeriesPoster,
						streamBadgeMediaSources = currentBaseRowItem.streamBadgeMediaSources,
					)
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
