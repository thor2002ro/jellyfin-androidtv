package org.jellyfin.androidtv.ui.playback.overlay

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.leanback.widget.PlaybackSeekDataProvider
import coil3.ImageLoader
import coil3.network.httpHeaders
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.request.transformations
import coil3.size.Dimension
import coil3.size.Size
import coil3.toBitmap
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.util.apiclient.getTrickplayImage
import org.jellyfin.androidtv.util.coil.SubsetTransformation
import org.jellyfin.sdk.api.client.ApiClient
import java.util.concurrent.atomic.AtomicInteger

class CustomSeekProvider(
	private val videoPlayerAdapter: VideoPlayerAdapter,
	private val imageLoader: ImageLoader,
	private val api: ApiClient,
	private val context: Context,
	private val trickPlayEnabled: Boolean,
	private val interval: Long
) : PlaybackSeekDataProvider() {
	private var imageRequest: Disposable? = null
	private val imageRequestId = AtomicInteger(0)
	private var currentSeekPositions = LongArray(0)

	private var cachedPlaceholderThumbnail: Bitmap? = null

	override fun getSeekPositions(): LongArray {
		if (!videoPlayerAdapter.canSeek()) return LongArray(0)

		val currentSeekPosition = videoPlayerAdapter.currentPosition
		val videoEndPosition = videoPlayerAdapter.duration

		val firstSeekPosition = currentSeekPosition % interval
		val lastSeekPosition = videoEndPosition - ((videoEndPosition - currentSeekPosition) % interval)
		val seekPositionCount = ((lastSeekPosition - firstSeekPosition) / interval).toInt() + 1

		val seekPositions = ArrayList<Long>(seekPositionCount + 2) // intermediate seek positions + beginning + end position
		seekPositions.add(0L)
		// Omit the first seek position if it represents the beginning of the video
		if (firstSeekPosition != 0L) {
			seekPositions.add(firstSeekPosition)
		}
		// Add all available seek positions but the last one
		for (i in 1..<seekPositionCount - 1) {
			seekPositions.add(firstSeekPosition + (i * interval))
		}
		// Omit the last seek position if it represents the end of the video
		if (lastSeekPosition != videoEndPosition) {
			seekPositions.add(lastSeekPosition)
		}
		// Omit the video end seek position if it represents the beginning of the video
		if (videoEndPosition != 0L) {
			seekPositions.add(videoEndPosition)
		}

		currentSeekPositions = seekPositions.toLongArray()
		return currentSeekPositions
	}

	override fun getThumbnail(index: Int, callback: ResultCallback) {
		if (index !in currentSeekPositions.indices) return

		val currentTimeMs = currentSeekPositions[index]
		val item = videoPlayerAdapter.currentlyPlayingItem ?: return
		val trickplayImage = item.getTrickplayImage(api, videoPlayerAdapter.currentMediaSource?.id, currentTimeMs) ?: return
		val placeholderThumbnail = getPlaceholderThumbnail(trickplayImage.width, trickplayImage.height)
		val requestId = imageRequestId.incrementAndGet()

		if (imageRequest?.isDisposed == false) imageRequest?.dispose()
		callback.onThumbnailLoaded(placeholderThumbnail, index)

		imageRequest = imageLoader.enqueue(ImageRequest.Builder(context).apply {
			data(trickplayImage.url)
			size(Size.ORIGINAL)
			maxBitmapSize(Size(Dimension.Undefined, Dimension.Undefined))
			httpHeaders(trickplayImage.headers)

			transformations(
				SubsetTransformation(
					trickplayImage.offsetX,
					trickplayImage.offsetY,
					trickplayImage.width,
					trickplayImage.height,
				)
			)

			target(
				onError = { _ ->
					if (requestId == imageRequestId.get()) callback.onThumbnailLoaded(placeholderThumbnail, index)
				},
				onSuccess = { image ->
					if (requestId == imageRequestId.get()) callback.onThumbnailLoaded(image.toBitmap(), index)
				}
			)
		}.build())
	}

	fun prefetchTileSheet(timeMs: Long) {
		if (!trickPlayEnabled) return

		val item = videoPlayerAdapter.currentlyPlayingItem ?: return
		val trickplayImage = item.getTrickplayImage(api, videoPlayerAdapter.currentMediaSource?.id, timeMs) ?: return

		imageLoader.enqueue(ImageRequest.Builder(context).apply {
			data(trickplayImage.url)
			httpHeaders(trickplayImage.headers)
		}.build())
	}

	override fun reset() {
		imageRequestId.incrementAndGet()
		if (imageRequest?.isDisposed == false) imageRequest?.dispose()
		imageRequest = null

		currentSeekPositions = LongArray(0)
	}

	fun getPlaceholderThumbnail(width: Int, height: Int): Bitmap {
		if (cachedPlaceholderThumbnail?.width == width && cachedPlaceholderThumbnail?.height == height) {
			return cachedPlaceholderThumbnail!!
		}

		val color = ContextCompat.getColor(context, R.color.black_transparent_light)
		val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		result.eraseColor(color)
		cachedPlaceholderThumbnail = result
		return result
	}
}
