package org.jellyfin.androidtv.ui.player.video

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ConcurrentHashMap

internal object ChapterThumbnailMemoryCache {
	private val thumbnails = ConcurrentHashMap<String, Bitmap>()

	fun put(url: String, bitmap: Bitmap) {
		thumbnails[url] = bitmap
	}

	fun get(url: String): ImageBitmap? = thumbnails[url]?.asImageBitmap()

	fun stats(urls: Collection<String>): TrickplayTileSheetMemoryStats {
		val bitmaps = urls.mapNotNull(thumbnails::get)
		return TrickplayTileSheetMemoryStats(
			count = bitmaps.size,
			bytes = bitmaps.sumOf { bitmap -> bitmap.allocationByteCount.toLong() },
		)
	}

	fun clear(urls: Collection<String>): TrickplayTileSheetMemoryStats {
		var count = 0
		var bytes = 0L
		urls.forEach { url ->
			val bitmap = thumbnails.remove(url) ?: return@forEach
			count++
			bytes += bitmap.allocationByteCount.toLong()
		}
		return TrickplayTileSheetMemoryStats(count, bytes)
	}
}
