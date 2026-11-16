package org.jellyfin.androidtv.ui.browsing

import android.content.Context
import coil3.ImageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.constant.LibraryViewStyle
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.resolveBrowseImageRequestSize
import org.jellyfin.androidtv.util.Utils.convertDpToPixel
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class BrowseImagePrefetcher {
	private val requests = mutableMapOf<String, Disposable>()
	private var lastPosition = -1
	private var lastItemsLoaded = -1

	fun prefetch(
		context: Context,
		adapter: ItemRowAdapter,
		api: ApiClient,
		imageLoader: ImageLoader,
		folder: BaseItemDto,
		viewStyle: LibraryViewStyle,
		imageType: ImageType,
		cardHeight: Int,
		cardsScreenEstimate: Int,
		cardsScreenStride: Int,
		position: Int,
	) {
		val itemsLoaded = adapter.itemsLoaded
		if (position < 0 || itemsLoaded <= 0) return
		if (lastPosition >= 0 && lastItemsLoaded == itemsLoaded &&
			abs(position - lastPosition) < max(1, cardsScreenEstimate)
		) return
		lastPosition = position
		lastItemsLoaded = itemsLoaded

		val prefetchImageType = if (viewStyle == LibraryViewStyle.DENSE_LIST) ImageType.THUMB else imageType
		val size = resolveBrowseImageRequestSize(
			viewStyle, getCardWidthBy(cardHeight.toDouble(), prefetchImageType, folder).toInt(), cardHeight,
		)
		val maxHeight = convertDpToPixel(context, size.heightDp)
		val maxWidth = convertDpToPixel(context, size.widthDp)
		val start = min(itemsLoaded, position + 1 + max(1, cardsScreenEstimate))
		val end = min(itemsLoaded, start + max(1, cardsScreenStride))
		val urls = LinkedHashSet<String>()
		for (index in start until end) {
			val item = adapter[index] as? BaseRowItem ?: continue
			val image = item.getImage(prefetchImageType) ?: continue
			urls.add(image.getUrl(api, maxWidth, maxHeight, null, null))
		}
		cancelObsoleteImagePrefetches(requests, urls)
		for (url in urls) {
			if (url !in requests) requests[url] = imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
		}
	}

	fun cancel() {
		cancelObsoleteImagePrefetches(requests, emptySet())
		lastPosition = -1
		lastItemsLoaded = -1
	}
}

internal fun cancelObsoleteImagePrefetches(requests: MutableMap<String, Disposable>, retainedUrls: Set<String>) {
	requests.entries.removeIf { (url, request) ->
		if (url in retainedUrls) false else {
			request.dispose()
			true
		}
	}
}
