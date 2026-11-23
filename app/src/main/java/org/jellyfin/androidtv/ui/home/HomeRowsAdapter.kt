package org.jellyfin.androidtv.ui.home

import androidx.collection.LruCache
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.jellyfin.androidtv.auth.repository.Session
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.BaseItemDto

internal class HomeRowsAdapter(presenter: Presenter) : MutableObjectAdapter<Row>(presenter) {
	val requests = HomeRowRequests()
	var cache: HomeRowsCache? = null
}

internal class HomeRowsCache private constructor() {
	val rows = LruCache<List<Any?>, List<BaseRowItem>>(64)
	var userViews: Collection<BaseItemDto>? = null

	companion object {
		private var cachedSession: Session? = null
		private var cache = HomeRowsCache()

		@Synchronized
		fun forSession(session: Session): HomeRowsCache {
			if (cachedSession !== session) {
				cachedSession = session
				cache = HomeRowsCache()
			}
			return cache
		}

		@Synchronized
		fun clear() {
			cachedSession = null
			cache = HomeRowsCache()
		}
	}
}

internal class HomeRowRequests {
	private val continuation = Mutex()

	suspend fun <T> loadPages(
		startIndex: Int,
		limit: Int,
		hasPublishedFirstPage: Boolean = false,
		deferPublication: Boolean = false,
		fetch: suspend (startIndex: Int, limit: Int) -> List<T>,
		publish: (List<T>, Boolean) -> Boolean,
	) {
		var fetched = 0
		val refreshedItems = mutableListOf<T>()
		while (fetched < limit) {
			val load: suspend () -> List<T> = { fetch(startIndex + fetched, minOf(5, limit - fetched)) }
			val append = hasPublishedFirstPage || fetched > 0
			val page = if (fetched > 0) {
				// Let ready first-page jobs start without waiting for their responses.
				yield()
				continuation.withLock { load() }
			} else {
				load()
			}
			if (deferPublication) {
				refreshedItems.addAll(page)
			} else if ((!append || page.isNotEmpty()) && !publish(page, append)) {
				break
			}
			if (page.isEmpty()) break
			fetched += page.size
		}
		if (deferPublication) publish(refreshedItems, false)
	}
}
