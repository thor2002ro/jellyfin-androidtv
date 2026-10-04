package org.jellyfin.androidtv.ui.browsing

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.AlphaPickerView
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetArtistsRequest
import org.jellyfin.sdk.model.api.request.GetAlbumArtistsRequest
import timber.log.Timber
import java.time.Instant

/** Counts belong to the library query, independently of the grid's ordering and current letter. */
class AlphabetPickerController(private val api: ApiClient, refresh: DataRefreshService) {
	private val counts = AlphabetLetterCounts(api, refresh)
	private var job: Job? = null
	private var currentKey: Triple<String?, Any?, String>? = null
	private var currentRevision: List<Instant?>? = null

	fun update(owner: LifecycleOwner, adapter: ItemRowAdapter, letters: String, horizontal: AlphaPickerView, vertical: AlphaPickerView) {
		val request = alphabetCountRequest(adapter.alphabetCountRequest())
		val key = Triple(api.baseUrl, request, letters)
		val revision = counts.revision()
		if (currentKey == key && currentRevision == revision && job?.isActive == true) return
		job?.cancel()
		currentKey = key
		currentRevision = revision
		// Apply a cache hit directly, avoiding two complete button rebuilds on filter switches.
		val cached = counts.cached(request, letters)
		horizontal.setLetters(cached ?: letters)
		vertical.setLetters(cached ?: letters)
		if (cached != null) return
		job = owner.lifecycleScope.launch {
			val present = counts.load(request, letters)
			// A filter can change while its grid request is still awaiting a response.
			if (Triple(api.baseUrl, alphabetCountRequest(adapter.alphabetCountRequest()), letters) != key) return@launch
			if (counts.revision() != revision) return@launch
			horizontal.setLetters(present)
			vertical.setLetters(present)
		}
	}

	fun cancel() {
		job?.cancel()
		job = null
		currentKey = null
		currentRevision = null
		counts.retryFailures()
	}
}

internal class AlphabetLetterCounts(private val api: ApiClient, private val refresh: DataRefreshService = DataRefreshService()) {
	private data class Result(val letters: String, val failed: Boolean)
	private val cache = linkedMapOf<Triple<String?, Any, String>, Result>()
	private var cacheRevision = revision()

	fun revision(): List<Instant?> = listOf(refresh.lastLibraryChange, refresh.lastPlayback, refresh.lastFavoriteUpdate)

	fun cached(request: Any?, letters: String): String? {
		val revision = revision()
		if (cacheRevision != revision) {
			cache.clear()
			cacheRevision = revision
		}
		val query = alphabetCountRequest(request) ?: return letters
		return cache[Triple(api.baseUrl, query, letters)]?.letters
	}

	fun retryFailures() {
		cache.entries.removeAll { it.value.failed }
	}

	@Suppress("TooGenericExceptionCaught") // Optional counts must fall back for any SDK/network failure.
	suspend fun load(request: Any?, letters: String): String {
		val query = alphabetCountRequest(request) ?: return letters
		cached(query, letters)?.let { return it }
		val revision = revision()
		val key = Triple(api.baseUrl, query, letters)
		var failed = false
		val present = try {
			withContext(Dispatchers.IO) {
				coroutineScope {
					val semaphore = Semaphore(LETTER_COUNT_CONCURRENCY)
					val candidates = letters.filter { it != '#' }
					val results = candidates.map { letter ->
						async { semaphore.withPermit { count(query, letter) } }
					}.awaitAll()
					ensureActive()
					// '#' is All in this browser. Unknown scripts/empty results retain the full bar.
					if (results.none { it > 0 }) letters
					else "#" + candidates.filterIndexed { index, _ -> results[index] > 0 }
				}
			}
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			Timber.w(error, "Unable to count library alphabet letters")
			failed = true
			letters
		}
		// Never publish or cache a narrowed result computed across a library/user-data change.
		if (revision != revision()) return letters
		// Failed passes suppress paging retries, but are discarded when the screen is re-entered.
		if (cache.size >= MAX_CACHED_ALPHABETS) cache.remove(cache.keys.first())
		cache[key] = Result(present, failed)
		return present
	}

	private suspend fun count(request: Any, letter: Char): Int = when (request) {
		is GetItemsRequest -> api.libraryApi.getItems(request.copy(nameStartsWith = letter.toString())).content.totalRecordCount
		is GetArtistsRequest -> api.artistApi.getArtists(request.copy(nameStartsWith = letter.toString())).content.totalRecordCount
		is GetAlbumArtistsRequest -> api.artistApi.getAlbumArtists(request.copy(nameStartsWith = letter.toString())).content.totalRecordCount
		else -> error("Unsupported alphabet query")
	}
}

internal fun alphabetCountRequest(request: Any?): Any? = when (request) {
	is GetItemsRequest -> request.copy(
		nameStartsWith = null, startIndex = 0, limit = 0, sortBy = null, sortOrder = null,
		fields = null, enableImages = false, enableImageTypes = null, enableUserData = false, enableTotalRecordCount = true,
	)
	is GetArtistsRequest -> request.copy(
		nameStartsWith = null, startIndex = 0, limit = 0, sortBy = null, sortOrder = null,
		fields = null, enableImages = false, enableImageTypes = null, enableUserData = false, enableTotalRecordCount = true,
	)
	is GetAlbumArtistsRequest -> request.copy(
		nameStartsWith = null, startIndex = 0, limit = 0, sortBy = null, sortOrder = null,
		fields = null, enableImages = false, enableImageTypes = null, enableUserData = false, enableTotalRecordCount = true,
	)
	else -> null
}

private const val LETTER_COUNT_CONCURRENCY = 5
private const val MAX_CACHED_ALPHABETS = 32
