package org.jellyfin.androidtv.ui.player.video

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.jellyfin.androidtv.util.apiclient.TrickplayImage
import java.util.concurrent.ConcurrentHashMap

internal data class TrickplayTileSheetMemoryStats(
	val count: Int,
	val bytes: Long,
) {
	val mib get() = bytes / 1024.0 / 1024.0
}

internal fun Map<String, Bitmap>.bitmapStats(urls: Collection<String>): TrickplayTileSheetMemoryStats {
	val bitmaps = urls.mapNotNull(::get)
	return TrickplayTileSheetMemoryStats(
		count = bitmaps.size,
		bytes = bitmaps.sumOf { bitmap -> bitmap.allocationByteCount.toLong() },
	)
}

internal fun MutableMap<String, Bitmap>.removeBitmaps(urls: Collection<String>): TrickplayTileSheetMemoryStats {
	var count = 0
	var bytes = 0L
	urls.forEach { url ->
		val bitmap = remove(url) ?: return@forEach
		count++
		bytes += bitmap.allocationByteCount.toLong()
	}
	return TrickplayTileSheetMemoryStats(count, bytes)
}

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

	fun stats(urls: Collection<String>) = sheets.bitmapStats(urls)

	fun clear(urls: Collection<String>) = sheets.removeBitmaps(urls)
}
