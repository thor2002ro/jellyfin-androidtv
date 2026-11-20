package org.jellyfin.androidtv.integration.dream.composable

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private data class Snowflake(
	val x: Float,
	val y: Float,
	val fallsPerLoop: Int,
	val size: Float,
)

@Composable
internal fun DreamSnow() {
	val snowflakes = remember {
		List(36) {
			Snowflake(Random.nextFloat(), Random.nextFloat(), Random.nextInt(3, 7), Random.nextFloat())
		}
	}
	val progress by rememberInfiniteTransition(label = "DreamSnow").animateFloat(
		initialValue = 0f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(tween(120_000, easing = LinearEasing)),
		label = "DreamSnowProgress",
	)

	Canvas(Modifier.fillMaxSize()) {
		val position = progress
		for (flake in snowflakes) {
			val radius = (0.8f + flake.size * 1.7f).dp.toPx()
			val fall = (flake.y + flake.fallsPerLoop * position) % 1f
			val drift = sin(2 * PI * (flake.y + 4 * position)).toFloat() * 0.018f
			drawCircle(
				color = Color.White,
				alpha = 0.35f + flake.size * 0.4f,
				radius = radius,
				center = Offset(
					x = (0.02f + flake.x * 0.96f + drift) * size.width,
					y = fall * (size.height + 2 * radius) - radius,
				),
			)
		}
	}
}
