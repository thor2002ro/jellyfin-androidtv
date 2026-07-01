package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.request.transformations
import coil3.size.Dimension
import coil3.size.Size
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.util.apiclient.TrickplayImage
import org.jellyfin.androidtv.util.apiclient.getTrickplayImage
import org.jellyfin.androidtv.util.coil.SubsetTransformation
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import kotlin.time.Duration

private val TrickplayThumbnailWidth = 180.dp

@Composable
fun VideoPlayerTrickplayThumbnail(
	item: BaseItemDto?,
	mediaSourceId: String?,
	position: Duration?,
	enabled: Boolean,
	modifier: Modifier = Modifier,
	api: ApiClient = koinInject(),
) {
	if (!enabled || item == null || position == null) return

	val timeMs = position.inWholeMilliseconds
	val trickplayImage = remember(item.id, item.trickplay, mediaSourceId, timeMs, api.accessToken) {
		item.getTrickplayImage(api, mediaSourceId, timeMs)
	} ?: return

	val request = rememberVideoPlayerTrickplayImageRequest(trickplayImage)

	Box(
		modifier = modifier
			.width(TrickplayThumbnailWidth)
			.aspectRatio(trickplayImage.width.toFloat() / trickplayImage.height)
			.clip(JellyfinTheme.shapes.medium)
			.background(Color.Black.copy(alpha = 0.45f))
	) {
		VideoPlayerTrickplayImage(
			request = request,
			modifier = Modifier.fillMaxSize(),
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
) {
	Image(
		painter = rememberAsyncImagePainter(request),
		contentDescription = null,
		contentScale = ContentScale.Crop,
		modifier = modifier,
	)
}
