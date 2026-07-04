package org.jellyfin.androidtv.ui.player.video

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.jellyfin.androidtv.util.apiclient.TrickplayImage
import java.util.concurrent.ConcurrentHashMap

internal object TrickplayTileSheetMemoryCache {
	private val sheets = ConcurrentHashMap<String, Bitmap>()

	fun put(url: String, bitmap: Bitmap) {
		sheets[url] = bitmap
	}

	fun getThumbnail(image: TrickplayImage): ImageBitmap? {
		val sheet = sheets[image.url] ?: return null
		return runCatching {
			Bitmap.createBitmap(
				sheet,
				image.offsetX,
				image.offsetY,
				image.width,
				image.height,
			).asImageBitmap()
		}.getOrNull()
	}

	fun clear(urls: Collection<String>) {
		urls.forEach(sheets::remove)
	}
}
