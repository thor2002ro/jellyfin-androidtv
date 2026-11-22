package org.jellyfin.androidtv.data.model

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.Instant
import java.util.UUID

class DataRefreshService {
	var lastDeletedItemId: UUID? = null
	var lastPlayback: Instant? = null
	var lastMoviePlayback: Instant? = null
	var lastTvPlayback: Instant? = null
	var lastLibraryChange: Instant? = null
	var lastFavoriteUpdate: Instant? = null
	var lastPlayedItem: BaseItemDto? = null

	private val watchedUpdate = MutableStateFlow<Instant?>(null)
	val lastWatchedUpdate = watchedUpdate.asStateFlow()

	fun notifyWatchedChanged() {
		watchedUpdate.value = Instant.now()
	}

	fun observeWatchedChanges(lifecycle: Lifecycle, refresh: (Instant) -> Unit) {
		lastWatchedUpdate
			.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.filterNotNull()
			.onEach { refresh(it) }
			.launchIn(lifecycle.coroutineScope)
	}

	fun notifyPlayback(item: BaseItemDto?) {
		if (item == null) return

		val now = Instant.now()
		lastPlayedItem = item
		lastPlayback = now
		when (item.type) {
			BaseItemKind.MOVIE -> lastMoviePlayback = now
			BaseItemKind.EPISODE,
			BaseItemKind.TV_CHANNEL,
			BaseItemKind.LIVE_TV_CHANNEL,
			BaseItemKind.PROGRAM,
			BaseItemKind.TV_PROGRAM,
			BaseItemKind.LIVE_TV_PROGRAM -> lastTvPlayback = now
			else -> Unit
		}
	}
}
