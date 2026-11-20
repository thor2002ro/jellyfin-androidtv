package org.jellyfin.androidtv.integration.dream.composable

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.composable.modifier.overscan

@Composable
internal fun DreamBouncingLogo() = BoxWithConstraints(
	modifier = Modifier.fillMaxSize().overscan(),
	contentAlignment = AbsoluteAlignment.TopLeft,
) {
	val painter = painterResource(R.drawable.app_logo)
	val aspectRatio = painter.intrinsicSize.width / painter.intrinsicSize.height
	val logoWidth = minOf(maxWidth * 0.28f, maxHeight * 0.2f * aspectRatio)
	val logoHeight = logoWidth / aspectRatio
	val travelX = maxWidth - logoWidth
	val travelY = maxHeight - logoHeight
	val bounce = rememberInfiniteTransition(label = "DreamLogoBounce")
	val horizontal by bounce.animateFloat(
		initialValue = 0f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(tween(15_000, easing = LinearEasing), RepeatMode.Reverse),
		label = "DreamLogoHorizontal",
	)
	val vertical by bounce.animateFloat(
		initialValue = 0f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(tween(9_500, easing = LinearEasing), RepeatMode.Reverse),
		label = "DreamLogoVertical",
	)

	Image(
		painter = painter,
		contentDescription = stringResource(R.string.app_name),
		modifier = Modifier.size(logoWidth, logoHeight).graphicsLayer {
			translationX = travelX.toPx() * horizontal
			translationY = travelY.toPx() * vertical
		},
	)
}
