package org.jellyfin.androidtv.integration.dream.composable

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import org.jellyfin.androidtv.integration.dream.model.DreamContent
import org.jellyfin.androidtv.preference.constant.ScreensaverMotion
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.ZoomBox
import org.jellyfin.androidtv.ui.composable.modifier.overscan

@Composable
fun DreamContentLibraryShowcase(
	content: DreamContent.LibraryShowcase,
	motion: ScreensaverMotion,
) = Box(
	modifier = Modifier.fillMaxSize(),
) {
	val effect = remember(content, motion) {
		if (motion == ScreensaverMotion.RANDOM) ScreensaverMotion.entries.filter { it != ScreensaverMotion.RANDOM }.random()
		else motion
	}
	val direction = if (content.item.id.hashCode() % 2 == 0) 1f else -1f
	MovingBackdrop(effect, direction) {
		Image(
			bitmap = content.backdrop.asImageBitmap(),
			contentDescription = null,
			alignment = Alignment.Center,
			contentScale = ContentScale.Crop,
			modifier = Modifier.fillMaxSize()
		)

		// Image vignette
		DreamContentVignette()
	}

	// Overlay
	Row(
		modifier = Modifier
			.align(Alignment.BottomStart)
			.overscan(),
	) {
		if (content.logo != null) {
			BoxWithConstraints {
				Image(
					bitmap = content.logo.asImageBitmap(),
					contentDescription = content.item.name,
					modifier = Modifier
						.sizeIn(
							maxWidth = maxWidth * 0.35f,
							maxHeight = 75.dp
						)
				)
			}
		} else {
			Text(
				text = content.item.name.orEmpty(),
				style = TextStyle(
					color = Color.White,
					fontSize = 32.sp
				),
			)
		}
	}
}

@Composable
private fun MovingBackdrop(
	motion: ScreensaverMotion,
	direction: Float,
	content: @Composable BoxScope.() -> Unit,
) {
	if (motion == ScreensaverMotion.PAN || motion == ScreensaverMotion.PAN_AND_ZOOM || motion == ScreensaverMotion.DIAGONAL_DRIFT) {
		val zooms = motion == ScreensaverMotion.PAN_AND_ZOOM
		val startScale = if (zooms) 1.04f else 1.1f
		val endScale = if (zooms) 1.14f else 1.1f
		val progress by rememberInfiniteTransition(label = "DreamPan").animateFloat(
			initialValue = 0f,
			targetValue = 1f,
			animationSpec = infiniteRepeatable(tween(30_000, 1_000, LinearEasing), RepeatMode.Reverse),
			label = "DreamPanProgress",
		)
		Box(Modifier.fillMaxSize().clipToBounds()) {
			Box(
				modifier = Modifier.fillMaxSize().graphicsLayer {
					val position = progress
					val scale = lerp(startScale, endScale, position)
					scaleX = scale
					scaleY = scale
					// Keep the pan inside the extra area created by the zoom.
					val offset = lerp(1f - startScale, endScale - 1f, position) * 0.45f
					translationX = size.width * offset * direction
					translationY = if (motion == ScreensaverMotion.DIAGONAL_DRIFT) size.height * offset * direction else 0f
				},
				content = content,
			)
		}
	} else {
		val zoomOut = motion == ScreensaverMotion.ZOOM_OUT
		ZoomBox(
			initialValue = if (zoomOut) 1.1f else 1f,
			targetValue = if (zoomOut) 1f else 1.1f,
			delayMillis = 1_000,
			durationMillis = 30_000,
			content = content,
		)
	}
}
