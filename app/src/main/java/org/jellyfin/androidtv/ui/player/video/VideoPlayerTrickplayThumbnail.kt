package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.request.transformations
import coil3.size.Dimension
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.util.apiclient.TrickplayImage
import org.jellyfin.androidtv.util.apiclient.getTrickplayImage
import org.jellyfin.androidtv.util.coil.SubsetTransformation
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import kotlin.time.Duration

internal val TrickplayThumbnailWidth = 180.dp

@Composable
fun VideoPlayerTrickplayThumbnail(
	item: BaseItemDto?,
	mediaSourceId: String?,
	position: Duration?,
	enabled: Boolean,
	modifier: Modifier = Modifier,
	api: ApiClient = koinInject(),
	imageLoader: ImageLoader = koinInject(),
) {
	if (!enabled || item == null || position == null) return

	val timeMs = position.inWholeMilliseconds
	val trickplayImage = remember(item.id, item.trickplay, mediaSourceId, timeMs, api.accessToken) {
		item.getTrickplayImage(api, mediaSourceId, timeMs)
	} ?: return
	val thumbnailAspectRatio = trickplayImage.width.toFloat() / trickplayImage.height

	Box(
		modifier = modifier
			.width(TrickplayThumbnailWidth)
			.aspectRatio(thumbnailAspectRatio)
			.clip(JellyfinTheme.shapes.medium)
			.background(Color.Black.copy(alpha = 0.45f))
	) {
		VideoPlayerTrickplayImage(
			request = rememberVideoPlayerTrickplayImageRequest(trickplayImage),
			imageLoader = imageLoader,
			requestKey = trickplayImage,
			lastSuccessKey = item.id to mediaSourceId,
			modifier = Modifier.fillMaxSize(),
		)
		Text(
			text = position.toThumbnailTimestamp(),
			style = JellyfinTheme.typography.listCaption.copy(color = Color.White),
			softWrap = false,
			maxLines = 1,
			overflow = TextOverflow.Clip,
			modifier = Modifier
				.align(Alignment.BottomEnd)
				.background(Color.Black.copy(alpha = 0.62f))
				.padding(horizontal = 8.dp, vertical = 4.dp)
		)
	}
}

@Composable
internal fun rememberVideoPlayerTrickplayImageRequest(trickplayImage: TrickplayImage): ImageRequest {
	val context = LocalContext.current
	return remember(context, trickplayImage) {
		ImageRequest.Builder(context)
			.data(trickplayImage.url)
			.size(Size.ORIGINAL)
			.maxBitmapSize(Size(Dimension.Undefined, Dimension.Undefined))
			.httpHeaders(trickplayImage.headers)
			.transformations(
				SubsetTransformation(
					trickplayImage.offsetX,
					trickplayImage.offsetY,
					trickplayImage.width,
					trickplayImage.height,
				)
			)
			.build()
	}
}

@Composable
internal fun VideoPlayerTrickplayImage(
	request: ImageRequest,
	modifier: Modifier = Modifier,
	imageLoader: ImageLoader = koinInject(),
	requestKey: Any = request,
	lastSuccessKey: Any? = Unit,
) {
	val latestRequest by rememberUpdatedState(request)
	val latestRequestKey by rememberUpdatedState(requestKey)
	var image by remember(lastSuccessKey) { mutableStateOf<ImageBitmap?>(null) }

	LaunchedEffect(imageLoader, lastSuccessKey) {
		var loadedKey: Any? = null
		while (isActive) {
			val nextKey = latestRequestKey
			if (nextKey == loadedKey) {
				snapshotFlow { latestRequestKey }.first { it != loadedKey }
				continue
			}

			val nextRequest = latestRequest
			runCatching {
				withContext(Dispatchers.IO) {
					(latestRequestKey as? TrickplayImage)?.let(TrickplayTileSheetMemoryCache::getThumbnail)
						?: imageLoader.execute(nextRequest).image?.toBitmap()?.asImageBitmap()
				}
			}.getOrNull()?.let { image = it }
			loadedKey = nextKey
		}
	}

	image?.let {
		Image(
			bitmap = it,
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = modifier,
		)
	}
}

private fun Duration.toThumbnailTimestamp(): String {
	val totalSeconds = inWholeSeconds.coerceAtLeast(0)
	val hours = totalSeconds / 3600
	val minutes = (totalSeconds % 3600) / 60
	val seconds = totalSeconds % 60

	return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
	else "%d:%02d".format(minutes, seconds)
}
