package org.jellyfin.androidtv.ui.composable.item

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
	val focusOverlayAlpha = integerResource(R.integer.card_focus_overlay_alpha_percent) / 100f

	Box(
		modifier = modifier
			.clip(shape)
			.background(backgroundColor, shape)
			.then(if (focused) Modifier.border(focusStrokeWidth, focusStroke, shape) else Modifier)
	) {
		image()

		if (focused) {
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
