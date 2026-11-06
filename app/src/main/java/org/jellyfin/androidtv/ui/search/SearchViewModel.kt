package org.jellyfin.androidtv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.BaseItemKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SearchViewModel(
	private val searchRepository: SearchRepository
) : ViewModel() {
	companion object {
		private val debounceDuration = 600.milliseconds

		private val groups = mapOf(
			R.string.lbl_movies to setOf(BaseItemKind.MOVIE),
			R.string.lbl_series to setOf(BaseItemKind.SERIES),
			R.string.lbl_episodes to setOf(BaseItemKind.EPISODE),
			R.string.lbl_videos to setOf(BaseItemKind.VIDEO),
			R.string.lbl_programs to setOf(BaseItemKind.LIVE_TV_PROGRAM),
			R.string.channels to setOf(BaseItemKind.LIVE_TV_CHANNEL),
			R.string.lbl_playlists to setOf(BaseItemKind.PLAYLIST),
			R.string.lbl_artists to setOf(BaseItemKind.MUSIC_ARTIST),
			R.string.lbl_albums to setOf(BaseItemKind.MUSIC_ALBUM),
			R.string.lbl_songs to setOf(BaseItemKind.AUDIO),
			R.string.photo_albums to setOf(BaseItemKind.PHOTO_ALBUM),
			R.string.photos to setOf(BaseItemKind.PHOTO),
			R.string.lbl_collections to setOf(BaseItemKind.BOX_SET),
			R.string.lbl_people to setOf(BaseItemKind.PERSON),
		)
	}

	private var searchJob: Job? = null

	private var previousQuery: String? = null
	private var resultsQuery: String? = null

	private val _searchResultsFlow = MutableStateFlow<Collection<SearchResultGroup>>(emptyList())
	val searchResultsFlow = _searchResultsFlow.asStateFlow()

	private val _hasSearchErrorFlow = MutableStateFlow(false)
	val hasSearchErrorFlow = _hasSearchErrorFlow.asStateFlow()

	fun searchImmediately(query: String) = search(query, 0.milliseconds, force = true)

	fun cancelSearch() {
		searchJob?.cancel()
		previousQuery = null
		_hasSearchErrorFlow.value = false
	}

	fun searchDebounced(query: String, debounce: Duration = debounceDuration) = search(query, debounce)

	private fun search(query: String, debounce: Duration, force: Boolean = false): Boolean {
		val trimmed = query.trim()
		if (!force && trimmed == previousQuery) return false
		val retainedResults = if (trimmed == resultsQuery) _searchResultsFlow.value else emptyList()
		previousQuery = trimmed

		searchJob?.cancel()
		_hasSearchErrorFlow.value = false

		if (trimmed.isBlank()) {
			resultsQuery = null
			_searchResultsFlow.value = emptyList()
			return true
		}

		searchJob = viewModelScope.launch {
			delay(debounce)
			resultsQuery = trimmed
			val results = groups.map { (label, _) ->
				retainedResults.firstOrNull { it.labelRes == label } ?: SearchResultGroup(label, emptyList())
			}.toMutableList()
			_searchResultsFlow.value = results.toList()
			val requests = Semaphore(3)
			groups.entries.forEachIndexed { index, (stringRes, itemKinds) ->
				launch {
					val result = requests.withPermit { searchRepository.search(trimmed, itemKinds) }
					ensureActive()
					result.fold(
						onSuccess = { items ->
							results[index] = SearchResultGroup(stringRes, items)
							_searchResultsFlow.value = results.toList()
						},
						onFailure = { _hasSearchErrorFlow.value = true },
					)
				}
			}
		}

		return true
	}
}
