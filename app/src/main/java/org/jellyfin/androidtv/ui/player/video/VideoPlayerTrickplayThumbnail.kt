package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.apiclient.TrickplayImage
import org.jellyfin.androidtv.util.apiclient.getTrickplayImage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import kotlin.time.Duration

internal val TrickplayThumbnailWidth = 180.dp

private data class TrickplaySheetCrop(
	val sheet: ImageBitmap,
	val crop: TrickplayThumbnailCrop,
)

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
			trickplayImage = trickplayImage,
			imageLoader = imageLoader,
			lastSuccessKey = item.id to mediaSourceId,
			modifier = Modifier.fillMaxSize(),
		)
		Text(
			text = TimeUtils.formatMillis(position.coerceAtLeast(Duration.ZERO).inWholeMilliseconds),
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
internal fun VideoPlayerTrickplayImage(
	trickplayImage: TrickplayImage,
	modifier: Modifier = Modifier,
	imageLoader: ImageLoader = koinInject(),
	lastSuccessKey: Any? = Unit,
) {
	val context = LocalContext.current
	val latestTrickplayImage by rememberUpdatedState(trickplayImage)
	var image by remember(lastSuccessKey) { mutableStateOf<TrickplaySheetCrop?>(null) }

	LaunchedEffect(imageLoader, lastSuccessKey) {
		var loadedKey: Any? = null
		while (isActive) {
			val nextImage = latestTrickplayImage
			val nextKey = nextImage
			if (nextKey == loadedKey) {
				snapshotFlow { latestTrickplayImage }.first { it != loadedKey }
				continue
			}

			runCatching {
				withContext(Dispatchers.IO) {
					val sheet = PlayerThumbnailMemoryCache.get(nextImage.sheet.url) ?: run {
						val bitmap = imageLoader.execute(
							nextImage.sheet.buildPlayerThumbnailRequest(context)
						).image?.toBitmap() ?: return@withContext null
						PlayerThumbnailMemoryCache.put(nextImage.sheet, bitmap)?.asImageBitmap()
							?: return@withContext null
					}
					TrickplaySheetCrop(
						sheet = sheet,
						crop = calculateTrickplayThumbnailCrop(nextImage, sheet.width, sheet.height),
					)
				}
			}.getOrNull()
				?.takeIf { latestTrickplayImage == nextImage }
				?.let { image = it }
			loadedKey = nextKey
		}
	}

	PlayerTrickplayThumbnail(image, modifier)
}

@Composable
internal fun VideoPlayerThumbnailImage(
	url: String,
	width: Int,
	height: Int,
	modifier: Modifier = Modifier,
	imageLoader: ImageLoader = koinInject(),
) {
	val context = LocalContext.current
	var image by remember(url) { mutableStateOf(PlayerThumbnailMemoryCache.get(url)) }

	LaunchedEffect(imageLoader, url, width, height) {
		if (image != null) return@LaunchedEffect
		withContext(Dispatchers.IO) {
			imageLoader.execute(buildPlayerThumbnailRequest(context, url, width, height)).image?.toBitmap()
		}?.let { bitmap ->
			PlayerThumbnailMemoryCache.put(url, bitmap)
			image = bitmap.asImageBitmap()
		}
	}

	PlayerThumbnail(image, modifier)
}

@Composable
private fun PlayerThumbnail(image: ImageBitmap?, modifier: Modifier) {
	image?.let { bitmap ->
		Image(
			bitmap = bitmap,
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = modifier,
		)
	}
}

@Composable
private fun PlayerTrickplayThumbnail(image: TrickplaySheetCrop?, modifier: Modifier) {
	if (image == null) return

	Canvas(modifier) {
		drawImage(
			image = image.sheet,
			srcOffset = IntOffset(image.crop.x, image.crop.y),
			srcSize = IntSize(image.crop.width, image.crop.height),
			dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
			filterQuality = FilterQuality.Medium,
		)
	}
}
