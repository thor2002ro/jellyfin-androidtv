package org.jellyfin.androidtv.data.model

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
