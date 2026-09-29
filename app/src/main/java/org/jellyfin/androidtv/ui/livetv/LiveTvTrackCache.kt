package org.jellyfin.androidtv.ui.livetv

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.playback.jellyfin.livetv.liveTvChannelId
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object LiveTvTrackCache {
	private const val MAX_CONCURRENT_LOOKUPS = 3
	private const val SHARED_PREFERENCES_NAME = "live_tv_tracks"

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val lookupLimit = Semaphore(MAX_CONCURRENT_LOOKUPS)
	private val tracks = ConcurrentHashMap<UUID, Tracks>()
	private val lookupAttempted = ConcurrentHashMap.newKeySet<UUID>()
	private val inFlight = ConcurrentHashMap<UUID, Deferred<Tracks?>>()
	private val json = Json {
		encodeDefaults = true
		ignoreUnknownKeys = true
	}
	@Volatile
	private var store: Store? = null

	@Serializable
	data class Tracks(
		val audio: List<Track> = emptyList(),
		val subtitles: List<Track> = emptyList(),
		val selectedAudioTrackIndex: Int? = null,
		val selectedSubtitleTrackIndex: Int? = null,
	) {
		val hasTracks get() = audio.isNotEmpty() || subtitles.isNotEmpty()
	}

	@Serializable
	data class Track(
		val index: Int? = null,
		val language: String? = null,
		val title: String? = null,
		val codec: String? = null,
		val isDefault: Boolean = false,
	)

	fun initialize(context: Context) {
		synchronized(this) {
			if (store != null) return

			val appContext = context.applicationContext
			store = Store(appContext).also { trackStore ->
				trackStore.load().forEach { (channelId, channelTracks) ->
					tracks.putIfAbsent(channelId, channelTracks)
				}
			}
		}
	}

	fun get(item: BaseItemDto): Tracks? =
		item.liveTvChannelId()?.let(tracks::get) ?: item.tracks()

	fun prefetchMissingOnce(
		api: ApiClient,
		items: Collection<BaseItemDto>,
		onResolved: (channelId: UUID) -> Unit,
	) {
		val channelIds = items
			.mapNotNull { item -> item.liveTvChannelId() }
			.distinct()
			.filter { channelId -> !tracks.containsKey(channelId) }
		if (channelIds.isEmpty()) return

		scope.launch {
			channelIds.forEach { channelId ->
				launch {
					val resolvedTracks = load(api, channelId)?.await()
					if (resolvedTracks != null) {
						withContext(Dispatchers.Main) { onResolved(channelId) }
					}
				}
			}
		}
	}

	fun update(
		channelId: UUID?,
		audio: List<Track>,
		subtitles: List<Track>,
		selectedAudioTrackIndex: Int?,
		selectedSubtitleTrackIndex: Int?,
	) {
		update(
			channelId,
			Tracks(
				audio = audio,
				subtitles = subtitles,
				selectedAudioTrackIndex = selectedAudioTrackIndex,
				selectedSubtitleTrackIndex = selectedSubtitleTrackIndex,
			)
		)
	}

	fun update(channelId: UUID?, channelTracks: Tracks?) {
		if (channelId == null || channelTracks?.hasTracks != true) return

		val cachedTracks = tracks[channelId]
		val updatedTracks = when {
			cachedTracks == null -> channelTracks
			cachedTracks == channelTracks -> null
			cachedTracks.hasSameTrackIdentity(channelTracks) && !channelTracks.hasDefaultFlags -> cachedTracks.mergeSelection(channelTracks)
			else -> channelTracks
		}
		if (updatedTracks == null || updatedTracks == cachedTracks) {
			lookupAttempted.add(channelId)
			return
		}

		tracks[channelId] = updatedTracks
		lookupAttempted.add(channelId)
		store?.save(channelId, updatedTracks)
	}

	fun updateSelectedAudioTrack(channelId: UUID?, trackIndex: Int?) {
		updateSelection(channelId) { cachedTracks ->
			cachedTracks.copy(selectedAudioTrackIndex = trackIndex)
		}
	}

	fun updateSelectedSubtitleTrack(channelId: UUID?, trackIndex: Int?) {
		updateSelection(channelId) { cachedTracks ->
			cachedTracks.copy(selectedSubtitleTrackIndex = trackIndex)
		}
	}

	@JvmStatic
	fun update(
		item: BaseItemDto?,
		mediaSource: MediaSourceInfo?,
		selectedAudioTrackIndex: Int?,
		selectedSubtitleTrackIndex: Int?,
	) {
		val channelId = item?.liveTvChannelId() ?: return
		update(channelId, mediaSource?.mediaStreams?.tracks(selectedAudioTrackIndex, selectedSubtitleTrackIndex))
	}

	@JvmStatic
	fun update(item: BaseItemDto?, mediaSource: MediaSourceInfo?) {
		val channelId = item?.liveTvChannelId() ?: return
		val source = mediaSource ?: return
		update(
			channelId,
			source.mediaStreams?.tracks(
				selectedAudioTrackIndex = source.defaultAudioStreamIndex,
				selectedSubtitleTrackIndex = source.defaultSubtitleStreamIndex,
			)
		)
	}

	private fun updateSelection(channelId: UUID?, update: (Tracks) -> Tracks) {
		if (channelId == null) return
		val cachedTracks = tracks[channelId] ?: return
		val updatedTracks = update(cachedTracks)
		if (updatedTracks == cachedTracks) return

		tracks[channelId] = updatedTracks
		store?.save(channelId, updatedTracks)
	}

	private fun load(api: ApiClient, channelId: UUID): Deferred<Tracks?>? {
		tracks[channelId]?.let { cachedTracks -> return CompletableDeferred(cachedTracks) }
		inFlight[channelId]?.let { return it }
		if (!lookupAttempted.add(channelId)) return null

		return inFlight.getOrPut(channelId) {
			scope.async {
				try {
					lookupLimit.withPermit {
						loadTracks(api, channelId)
					}.also { channelTracks ->
						if (channelTracks != null && tracks.putIfAbsent(channelId, channelTracks) == null) {
							store?.save(channelId, channelTracks)
						}
					}
				} finally {
					inFlight.remove(channelId)
				}
			}
		}
	}

	private suspend fun loadTracks(api: ApiClient, channelId: UUID): Tracks? = runCatching {
		val response = api.mediaInfoApi.getPostedPlaybackInfo(
			itemId = channelId,
			data = PlaybackInfoDto(
				enableDirectPlay = true,
				enableDirectStream = true,
				enableTranscoding = false,
				allowVideoStreamCopy = true,
				allowAudioStreamCopy = true,
				autoOpenLiveStream = false,
			)
		).content
		if (response.errorCode != null) return@runCatching null

		response.mediaSources
			.firstNotNullOfOrNull { source -> source.mediaStreams?.takeIf { streams -> streams.isNotEmpty() } }
			?.tracks()
	}.getOrNull()

	private fun BaseItemDto.tracks(): Tracks? =
		mediaSources.orEmpty()
			.firstNotNullOfOrNull { source -> source.mediaStreams?.takeIf { streams -> streams.isNotEmpty() } }
			?.tracks()

	private fun List<MediaStream>.tracks(
		selectedAudioTrackIndex: Int? = null,
		selectedSubtitleTrackIndex: Int? = null,
	): Tracks? {
		val audio = filter { stream -> stream.type == MediaStreamType.AUDIO }
			.map { stream -> stream.toCacheTrack() }
		val subtitles = filter { stream -> stream.type == MediaStreamType.SUBTITLE }
			.map { stream -> stream.toCacheTrack() }

		return Tracks(
			audio = audio,
			subtitles = subtitles,
			selectedAudioTrackIndex = selectedAudioTrackIndex,
			selectedSubtitleTrackIndex = selectedSubtitleTrackIndex,
		).takeIf(Tracks::hasTracks)
	}

	private fun MediaStream.toCacheTrack() = Track(
		index = index,
		language = language,
		title = displayTitle ?: title,
		codec = codec,
		isDefault = isDefault,
	)

	private val Tracks.hasDefaultFlags
		get() = audio.any { track -> track.isDefault } ||
			subtitles.any { track -> track.isDefault }

	private fun Tracks.hasSameTrackIdentity(other: Tracks) =
		audio.hasSameTrackIdentity(other.audio) &&
			subtitles.hasSameTrackIdentity(other.subtitles)

	private fun List<Track>.hasSameTrackIdentity(other: List<Track>) =
		size == other.size && zip(other).all { (first, second) -> first.hasSameTrackIdentity(second) }

	private fun Track.hasSameTrackIdentity(other: Track) =
		index == other.index &&
			language == other.language &&
			title == other.title &&
			codec == other.codec

	private fun Tracks.mergeSelection(other: Tracks) = copy(
		selectedAudioTrackIndex = other.selectedAudioTrackIndex ?: selectedAudioTrackIndex,
		selectedSubtitleTrackIndex = other.selectedSubtitleTrackIndex ?: selectedSubtitleTrackIndex,
	)

	private class Store(context: Context) {
		private val preferences = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)

		fun load(): Map<UUID, Tracks> = preferences.all.mapNotNull { (channelId, value) ->
			val id = runCatching { UUID.fromString(channelId) }.getOrNull() ?: return@mapNotNull null
			val channelTracks = (value as? String)
				?.let { storedValue -> runCatching { json.decodeFromString<Tracks>(storedValue) }.getOrNull() }
				?.takeIf(Tracks::hasTracks)
				?: return@mapNotNull null

			id to channelTracks
		}.toMap()

		fun save(channelId: UUID, channelTracks: Tracks) {
			preferences.edit()
				.putString(channelId.toString(), json.encodeToString(channelTracks))
				.apply()
		}
	}
}
