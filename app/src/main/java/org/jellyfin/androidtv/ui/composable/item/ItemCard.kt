package org.jellyfin.androidtv.ui.composable.item

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme

@Composable
@Stable
fun ItemCard(
	modifier: Modifier = Modifier,
	backgroundColor: Color = JellyfinTheme.colorScheme.surface,
	focused: Boolean = false,
	image: @Composable BoxScope.() -> Unit,
	overlay: (@Composable BoxScope.() -> Unit)? = null,
	shape: Shape = JellyfinTheme.shapes.medium,
) {
	val focusStart = colorResource(R.color.card_focus_gradient_start)
	val focusEnd = colorResource(R.color.card_focus_gradient_end)
	val focusStroke = colorResource(R.color.card_focus_stroke)
	val focusStrokeWidth = dimensionResource(R.dimen.card_focus_stroke_width)
	val focusGlow = dimensionResource(R.dimen.card_focus_glow)
	val focusSheen = colorResource(R.color.card_focus_sheen)
	val sheenDuration = integerResource(R.integer.card_focus_sheen_duration)
	val sheen = remember { Animatable(1f) }
	LaunchedEffect(focused, sheenDuration) {
		if (focused) {
			sheen.snapTo(0f)
			sheen.animateTo(1f, tween(sheenDuration))
		} else {
			sheen.snapTo(1f)
		}
	}
	val focusAmount by animateFloatAsState(
		targetValue = if (focused) 1f else 0f,
		animationSpec = tween(integerResource(R.integer.card_scale_duration)),
		label = "card_focus_decoration",
	)
	val focusOverlayAlpha = integerResource(R.integer.card_focus_overlay_alpha_percent) / 100f * focusAmount

	Box(
		modifier = modifier
			.shadow(
				elevation = focusGlow * focusAmount,
				shape = shape,
				clip = false,
				ambientColor = focusStart.copy(alpha = 0.35f),
				spotColor = focusEnd.copy(alpha = 0.45f),
			)
			.clip(shape)
			.background(backgroundColor, shape)
			.border(
				focusStrokeWidth,
				Brush.linearGradient(listOf(
					focusStroke.copy(alpha = focusAmount),
					focusStart.copy(alpha = focusAmount),
					focusEnd.copy(alpha = focusAmount),
				)),
				shape,
			)
	) {
		image()

		if (focusAmount > 0f) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(
						Brush.linearGradient(
							listOf(
								focusStart.copy(alpha = focusOverlayAlpha),
								Color.Transparent,
								focusEnd.copy(alpha = focusOverlayAlpha),
							)
						)
					)
					.drawWithContent {
						drawContent()
						if (sheen.value < 1f) {
							val band = size.minDimension * 0.3f
							val position = -2f * band + (size.width + size.height + 2f * band) * sheen.value
							drawRect(Brush.linearGradient(
								colors = listOf(Color.Transparent, focusSheen.copy(alpha = focusSheen.alpha * focusAmount), Color.Transparent),
								start = Offset(position, 0f),
								end = Offset(position + band, band),
							))
						}
					}
			)
		}

		if (overlay != null) {
			Box(
				modifier = Modifier.fillMaxSize(),
				content = overlay
			)
		}
	}
}
