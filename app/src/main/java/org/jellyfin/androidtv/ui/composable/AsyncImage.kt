package org.jellyfin.androidtv.ui.composable

import android.app.ActivityManager
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toDrawable
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.AsyncImage as CoilAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.util.BlurHashDecoder
import org.koin.compose.koinInject
import kotlin.math.round

private data class AsyncImageState(
	val url: String?,
	val blurHash: String?,
)

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
	val compositionState = remember(url, blurHash) { AsyncImageState(url, blurHash) }
	val isLowRamDevice = remember { context.getSystemService<ActivityManager>()?.isLowRamDevice == true }
	val blurHashDrawable by produceState<Drawable?>(null, compositionState, aspectRatio, blurHashResolution, isLowRamDevice) {
		value = if (compositionState.url != null && compositionState.blurHash != null && !isLowRamDevice && aspectRatio > 0) {
			withContext(Dispatchers.IO) {
				BlurHashDecoder.decode(
					compositionState.blurHash,
					if (aspectRatio > 1) round(blurHashResolution * aspectRatio).toInt() else blurHashResolution,
					if (aspectRatio >= 1) blurHashResolution else round(blurHashResolution / aspectRatio).toInt(),
				)?.toDrawable(context.resources)
			}
		} else null
	}

	val request = remember(compositionState, placeholder, blurHashDrawable) {
		ImageRequest.Builder(context).apply {
			data(compositionState.url ?: placeholder)
			placeholder((blurHashDrawable ?: placeholder)?.asImage())
			error(placeholder?.asImage())
			crossfade(100)
		}.build()
	}

	CoilAsyncImage(
		model = request,
		contentDescription = null,
		imageLoader = imageLoader,
		modifier = modifier,
		contentScale = scaleType.toContentScale(),
	)
}

private fun ImageView.ScaleType?.toContentScale() = when (this) {
	ImageView.ScaleType.CENTER_CROP -> ContentScale.Crop
	ImageView.ScaleType.FIT_XY -> ContentScale.FillBounds
	ImageView.ScaleType.CENTER -> ContentScale.None
	else -> ContentScale.Fit
}

@Composable
fun blurHashPainter(
	blurHash: String,
	size: IntSize,
	punch: Float = 1f,
): Painter = remember(blurHash, size, punch) {
	val bitmap = BlurHashDecoder.decode(
		blurHash = blurHash,
		width = size.width,
		height = size.height,
		punch = punch,
	)

	BitmapPainter(requireNotNull(bitmap).asImageBitmap())
}
