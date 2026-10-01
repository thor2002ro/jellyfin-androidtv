package org.jellyfin.androidtv.ui.composable

import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.AsyncImage as CoilAsyncImage
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.util.BlurHashDecoder
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.BlurHashRequest
import org.jellyfin.androidtv.util.createBlurHashDecodeRequest
import org.jellyfin.androidtv.util.createBlurHashRequest
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.compose.koinInject

@Composable
fun AsyncImage(
	image: JellyfinImage?,
	modifier: Modifier = Modifier,
	placeholder: Drawable? = null,
	aspectRatio: Float = image?.aspectRatio ?: 1f,
	blurHashResolution: Int = 32,
	scaleType: ImageView.ScaleType? = null,
	maxWidth: Int? = null,
	maxHeight: Int? = null,
	fillWidth: Int? = null,
	fillHeight: Int? = null,
) {
	val api = koinInject<ApiClient>()
	AsyncImage(
		url = image?.getUrl(api, maxWidth, maxHeight, fillWidth, fillHeight),
		blurHash = image?.blurHash,
		modifier = modifier,
		placeholder = placeholder,
		aspectRatio = aspectRatio,
		blurHashResolution = blurHashResolution,
		scaleType = scaleType,
	)
}

@Composable
fun AsyncImage(
	modifier: Modifier = Modifier,
	url: String? = null,
	blurHash: String? = null,
	placeholder: Drawable? = null,
	aspectRatio: Float = 1f,
	blurHashResolution: Int = 32,
	scaleType: ImageView.ScaleType? = null,
) {
	val context = LocalContext.current
	val imageLoader = koinInject<ImageLoader>()
	val isLowRamDevice = remember { context.getSystemService<ActivityManager>()?.isLowRamDevice == true }
	val blurHashRequest = remember(url, blurHash, isLowRamDevice, aspectRatio, blurHashResolution) {
		createBlurHashRequest(url, blurHash, isLowRamDevice, aspectRatio.toDouble(), blurHashResolution)
	}
	val blurHashBitmap by rememberBlurHashBitmap(blurHashRequest)

	val request = remember(context, url) {
		ImageRequest.Builder(context).apply {
			data(url)
			crossfade(100)
		}.build()
	}
	val placeholderPainter = remember(context, blurHashBitmap, placeholder) {
		blurHashBitmap?.let { BitmapPainter(it.asImageBitmap()) }
			?: placeholder?.asImage()?.asPainter(context)
	}
	val errorPainter = remember(context, placeholder) { placeholder?.asImage()?.asPainter(context) }

	CoilAsyncImage(
		model = request,
		contentDescription = null,
		imageLoader = imageLoader,
		modifier = modifier,
		placeholder = placeholderPainter,
		error = errorPainter,
		fallback = errorPainter,
		contentScale = scaleType.toContentScale(),
	)
}

@Composable
fun BlurHashImage(
	blurHash: String?,
	modifier: Modifier = Modifier,
	aspectRatio: Float = 1f,
	blurHashResolution: Int = 32,
	scaleType: ImageView.ScaleType? = null,
	content: @Composable BoxScope.() -> Unit = {},
) {
	val context = LocalContext.current
	val isLowRamDevice = remember { context.getSystemService<ActivityManager>()?.isLowRamDevice == true }
	val request = remember(blurHash, isLowRamDevice, aspectRatio, blurHashResolution) {
		createBlurHashDecodeRequest(blurHash, isLowRamDevice, aspectRatio.toDouble(), blurHashResolution)
	}
	val bitmap by rememberBlurHashBitmap(request)

	Box(modifier = modifier) {
		bitmap?.let { decodedBitmap ->
			Image(
				bitmap = decodedBitmap.asImageBitmap(),
				contentDescription = null,
				contentScale = scaleType.toContentScale(),
				modifier = Modifier.fillMaxSize(),
			)
		}
		content()
	}
}

@Composable
private fun rememberBlurHashBitmap(request: BlurHashRequest?): State<Bitmap?> = produceState(null, request) {
	value = request?.let {
		withContext(Dispatchers.Default) { BlurHashDecoder.decode(it.blurHash, it.width, it.height) }
	}
}

private fun ImageView.ScaleType?.toContentScale() = when (this) {
	ImageView.ScaleType.CENTER_CROP -> ContentScale.Crop
	ImageView.ScaleType.FIT_XY -> ContentScale.FillBounds
	ImageView.ScaleType.CENTER -> ContentScale.None
	else -> ContentScale.Fit
}
