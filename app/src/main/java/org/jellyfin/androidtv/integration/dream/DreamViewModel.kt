package org.jellyfin.androidtv.integration.dream

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.BlackholeDecoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jellyfin.androidtv.integration.dream.model.DreamContent
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.ScreensaverBackdropSource
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.remoteImageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@SuppressLint("StaticFieldLeak")
class DreamViewModel(
	private val api: ApiClient,
	private val imageLoader: ImageLoader,
	private val context: Context,
	playbackManager: PlaybackManager,
	private val userPreferences: UserPreferences,
) : ViewModel() {
	@OptIn(ExperimentalCoroutinesApi::class)
	private val _mediaContent = playbackManager.queue.entry
		.combine(playbackManager.state.playState) { entry, playState ->
			entry
				?.takeIf { playState == PlayState.PLAYING }
				?.takeIf { it.visibleInScreensaver }
				?.let { visibleEntry ->
					visibleEntry.baseItem?.let { baseItem -> DreamContent.NowPlaying(visibleEntry, baseItem) }
				}
		}
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

	val pausedContent = playbackManager.queue.entry
		.combine(playbackManager.state.playState) { entry, playState ->
			entry
				?.takeIf { playState == PlayState.PAUSED }
				?.let { visibleEntry ->
					visibleEntry.baseItem?.let { baseItem -> DreamContent.NowPlaying(visibleEntry, baseItem) }
				}
		}
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

	private val _libraryContent = flow {
		// Load first library item after 2 seconds
		// to force the logo at the start of the screensaver
		emit(null)
		delay(2.seconds)

		val requireParentalRating = userPreferences[UserPreferences.screensaverAgeRatingRequired]
		val maxParentalRating = userPreferences[UserPreferences.screensaverAgeRatingMax]
		emitAll(
			getRandomLibraryShowcaseItems(
				requireParentalRating = requireParentalRating,
				maxParentalRating = maxParentalRating,
				batchSize = 60,
				emitDelay = 30.seconds,
				noItemsDelay = 2.minutes,
				errorDelay = 3.seconds,
			)
		)
	}
		.distinctUntilChanged()
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), null)

	val content = combine(_mediaContent, _libraryContent) { mediaContent, libraryContent ->
		mediaContent ?: libraryContent ?: DreamContent.Logo
	}.stateIn(
		scope = viewModelScope,
		started = SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
		initialValue = DreamContent.Logo,
	)

	private fun getRandomLibraryShowcaseItems(
		requireParentalRating: Boolean,
		maxParentalRating: Int,
		batchSize: Int,
		emitDelay: Duration,
		noItemsDelay: Duration,
		errorDelay: Duration,
	): Flow<DreamContent.LibraryShowcase?> = flow {
		val useServerProviders = userPreferences[UserPreferences.screensaverBackdropSource] == ScreensaverBackdropSource.SERVER_PROVIDERS
		while (true) {
			val items = try {
				withContext(Dispatchers.IO) {
					val response by api.libraryApi.getItems(
						includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
						recursive = true,
						sortBy = listOf(ItemSortBy.RANDOM),
						limit = batchSize,
						imageTypes = listOf(ImageType.BACKDROP),
						maxOfficialRating = if (maxParentalRating == -1) null else maxParentalRating.toString(),
						hasParentalRating = if (requireParentalRating) true else null,
					)
					response.items
				}
			} catch (err: ApiClientException) {
				Timber.w(err, "Unable to load dream items")
				null
			}

			if (items == null) {
				emit(null)
				delay(errorDelay)
			} else if (items.isEmpty()) {
				emit(null)
				delay(noItemsDelay)
			} else {
				val backdrops = items.flatMap { item ->
					val urls = item.itemBackdropImages.distinctBy { it.tag }.shuffled().take(3)
						.map { image -> image.getUrl(api) }
					if (urls.isEmpty() && useServerProviders) listOf(item to null)
					else urls.map { url -> item to url }
				}.shuffled()
				val remoteUrlsByItem = mutableMapOf<BaseItemDto, Map<String, List<String>>>()
				val remoteUrlsBySlide = mutableMapOf<Int, String?>()
				suspend fun externalBackdrop(index: Int): String? {
					if (!useServerProviders) return null
					if (!remoteUrlsBySlide.containsKey(index)) {
						val item = backdrops[index].first
						val providers = remoteUrlsByItem.getOrPut(item) { loadRemoteBackdrops(item) }
						remoteUrlsBySlide[index] = providers.values.randomOrNull()?.randomOrNull()
					}
					return remoteUrlsBySlide[index]
				}
				var displayedSlide = false
				for ((index, candidate) in backdrops.withIndex()) {
					val (item, backdropUrl) = candidate
					val showcase = externalBackdrop(index)?.let { item.asLibraryShowcase(it) }
						?: backdropUrl?.let { item.asLibraryShowcase(it) } ?: continue
					displayedSlide = true
					emit(showcase)
					coroutineScope {
						val prefetch = launch {
							for (next in index + 1..minOf(index + 2, backdrops.lastIndex)) {
								val nextUrl = externalBackdrop(next) ?: backdrops[next].second
								if (nextUrl != null) prefetchBackdrop(nextUrl)
							}
						}
						delay(emitDelay)
						prefetch.cancel()
					}
				}
				if (!displayedSlide) {
					emit(null)
					delay(errorDelay)
				}
			}
		}
	}.cancellable()

	private suspend fun loadRemoteBackdrops(item: BaseItemDto): Map<String, List<String>> = withTimeoutOrNull(8.seconds) {
		try {
			val response by api.remoteImageApi.getRemoteImages(itemId = item.id, type = ImageType.BACKDROP)
			response.images.orEmpty().mapNotNull { image ->
				val url = image.url?.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
				url?.let { image.providerName.orEmpty() to it.toString() }
			}.groupBy({ it.first }, { it.second })
		} catch (error: ApiClientException) {
			Timber.d(error, "Unable to look up remote screensaver backdrops")
			emptyMap()
		}
	} ?: emptyMap()

	@OptIn(ExperimentalCoilApi::class)
	private suspend fun prefetchBackdrop(url: String) {
		imageLoader.execute(
			ImageRequest.Builder(context)
				.data(url)
				.memoryCachePolicy(CachePolicy.DISABLED)
				.decoderFactory(BlackholeDecoder.Factory())
				.build(),
		)
	}

	private suspend fun BaseItemDto.asLibraryShowcase(backdropUrl: String): DreamContent.LibraryShowcase? = withContext(Dispatchers.IO) {
		val logoUrl = itemImages[ImageType.LOGO]?.getUrl(api)

		// Only attempt to load logo if there is one, wrap in async {} to load it parallel with the backdrop
		val logo = logoUrl?.let { url ->
			async {
				imageLoader.execute(
					request = ImageRequest.Builder(context).data(url).build()
				).image?.toBitmap()
			}
		}

		val backdrop = imageLoader.execute(
			request = ImageRequest.Builder(context).data(backdropUrl).build()
		).image?.toBitmap()

		if (backdrop == null) null
		else DreamContent.LibraryShowcase(this@asLibraryShowcase, backdrop, logo?.await())
	}
}
