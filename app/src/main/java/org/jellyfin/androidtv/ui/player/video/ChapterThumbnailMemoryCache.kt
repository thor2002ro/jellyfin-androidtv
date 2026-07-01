package org.jellyfin.androidtv.ui.player.video

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.sdk.buildChapterItems
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.concurrent.ConcurrentHashMap

internal object ChapterThumbnailMemoryCache {
	private val thumbnails = ConcurrentHashMap<String, Bitmap>()

	fun put(url: String, bitmap: Bitmap) {
		thumbnails[url] = bitmap
	}

	fun get(url: String): ImageBitmap? = thumbnails[url]?.asImageBitmap()

	fun stats(urls: Collection<String>) = thumbnails.bitmapStats(urls)

	fun clear(urls: Collection<String>) = thumbnails.removeBitmaps(urls)
}

internal fun BaseItemDto.getChapterThumbnailUrls(
	api: ApiClient,
	fillWidth: Int,
	fillHeight: Int,
): List<String> = buildChapterItems()
	.mapNotNull { chapter -> chapter.image }
	.map { image ->
		image.getUrl(
			api = api,
			fillWidth = fillWidth,
			fillHeight = fillHeight,
		)
	}
	.distinct()
