package org.jellyfin.androidtv.ui.player.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.size.Size
import org.jellyfin.androidtv.util.apiclient.TrickplayImage
import org.jellyfin.androidtv.util.apiclient.TrickplayTileSheet
import org.jellyfin.androidtv.util.apiclient.TRICKPLAY_THUMBNAIL_PIXEL_HEIGHT
import org.jellyfin.androidtv.util.apiclient.TRICKPLAY_THUMBNAIL_PIXEL_WIDTH

internal const val PLAYER_THUMBNAIL_CACHE_MAX_BYTES = 15 * 1024 * 1024

internal data class PlayerThumbnailMemoryStats(
	val count: Int,
	val bytes: Long,
) {
	val mib get() = bytes / 1024.0 / 1024.0
}

internal data class TrickplayThumbnailCrop(
	val x: Int,
	val y: Int,
	val width: Int,
	val height: Int,
)

internal fun calculateTrickplayThumbnailCrop(
	image: TrickplayImage,
	decodedSheetWidth: Int,
	decodedSheetHeight: Int,
): TrickplayThumbnailCrop {
	val tileWidth = decodedSheetWidth / image.sheet.columns
	val tileHeight = decodedSheetHeight / image.sheet.rows
	return TrickplayThumbnailCrop(
		x = image.offsetX / image.width * tileWidth,
		y = image.offsetY / image.height * tileHeight,
		width = tileWidth,
		height = tileHeight,
	)
}

internal object PlayerThumbnailMemoryCache {
	private val bitmaps = object : LruCache<String, Bitmap>(PLAYER_THUMBNAIL_CACHE_MAX_BYTES) {
		override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
	}

	fun put(url: String, bitmap: Bitmap) = bitmaps.put(url, bitmap)
	fun put(sheet: TrickplayTileSheet, bitmap: Bitmap) = put(sheet.url, bitmap)
	fun get(url: String): ImageBitmap? = bitmaps[url]?.asImageBitmap()

	fun getThumbnailBitmap(image: TrickplayImage): Bitmap? {
		val sheet = bitmaps[image.sheet.url] ?: return null
		val crop = calculateTrickplayThumbnailCrop(
			image = image,
			decodedSheetWidth = sheet.width,
			decodedSheetHeight = sheet.height,
		)
		return runCatching {
			val matrix = Matrix().apply {
				setScale(
					TRICKPLAY_THUMBNAIL_PIXEL_WIDTH.toFloat() / crop.width,
					TRICKPLAY_THUMBNAIL_PIXEL_HEIGHT.toFloat() / crop.height,
				)
			}
			Bitmap.createBitmap(
				sheet,
				crop.x,
				crop.y,
				crop.width,
				crop.height,
				matrix,
				true,
			)
		}.getOrNull()
	}

	fun getThumbnail(image: TrickplayImage): ImageBitmap? = getThumbnailBitmap(image)?.asImageBitmap()

	fun stats(urls: Collection<String>): PlayerThumbnailMemoryStats {
		val matching = bitmaps.snapshot().filterKeys(urls::contains).values
		return PlayerThumbnailMemoryStats(
			count = matching.size,
			bytes = matching.sumOf { bitmap -> bitmap.allocationByteCount.toLong() },
		)
	}

	fun clear(urls: Collection<String>): PlayerThumbnailMemoryStats {
		var count = 0
		var bytes = 0L
		urls.forEach { url ->
			val bitmap = bitmaps.remove(url) ?: return@forEach
			count++
			bytes += bitmap.allocationByteCount.toLong()
		}
		return PlayerThumbnailMemoryStats(count, bytes)
	}
}

internal fun buildPlayerThumbnailRequest(
	context: Context,
	url: String,
	width: Int,
	height: Int,
	headers: NetworkHeaders? = null,
): ImageRequest = ImageRequest.Builder(context)
	.data(url)
	.size(Size(width, height))
	.bitmapConfig(Bitmap.Config.RGB_565)
	.allowHardware(false)
	.apply { headers?.let(::httpHeaders) }
	.memoryCachePolicy(CachePolicy.DISABLED)
	.diskCachePolicy(CachePolicy.DISABLED)
	.build()

internal fun TrickplayTileSheet.buildPlayerThumbnailRequest(context: Context) = buildPlayerThumbnailRequest(
	context = context,
	url = url,
	width = decodeWidth,
	height = decodeHeight,
	headers = headers,
)
