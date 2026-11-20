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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.integration.dream.DreamWeatherCondition
import kotlin.math.abs
import kotlin.random.Random

@Composable
internal fun DreamWeatherOverlay(condition: DreamWeatherCondition) {
	when (condition) {
		DreamWeatherCondition.PARTLY_CLOUDY -> DreamClouds(count = 3, fog = false)
		DreamWeatherCondition.CLOUDY -> DreamClouds(count = 6, fog = false)
		DreamWeatherCondition.FOG -> DreamClouds(count = 5, fog = true)
		DreamWeatherCondition.DRIZZLE -> DreamRain(drizzle = true, storm = false)
		DreamWeatherCondition.RAIN -> DreamRain(drizzle = false, storm = false)
		DreamWeatherCondition.THUNDERSTORM -> DreamRain(drizzle = false, storm = true)
		DreamWeatherCondition.CLEAR, DreamWeatherCondition.SNOW, DreamWeatherCondition.UNKNOWN -> Unit
	}
}

@Composable
private fun DreamClouds(count: Int, fog: Boolean) {
	val progress by rememberInfiniteTransition(label = "DreamClouds").animateFloat(
		initialValue = 0f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(tween(75_000, easing = LinearEasing)),
		label = "Cloud progress",
	)
	Canvas(Modifier.fillMaxSize().clipToBounds()) {
		val phase = progress
		for (index in 0 until count) {
			val drift = (phase + index.toFloat() / count) % 1f
			val x = (drift * 2.3f - 0.65f) * size.width
			val y = size.height * if (fog) 0.35f + index * 0.13f else 0.05f + (index % 3) * 0.075f
			val alpha = if (fog) 0.30f else 0.22f
			drawHaze(x, y, size.width * (if (fog) 0.62f else 0.27f), size.height * (if (fog) 0.14f else 0.09f), alpha)
			if (!fog) drawHaze(x + size.width * 0.13f, y - size.height * 0.03f, size.width * 0.18f, size.height * 0.075f, alpha)
		}
	}
}

private data class Raindrop(val x: Float, val y: Float, val fallsPerLoop: Int)

@Composable
private fun DreamRain(drizzle: Boolean, storm: Boolean) {
	val drops = remember(drizzle, storm) {
		val count = if (drizzle) 48 else if (storm) 120 else 90
		List(count) {
			Raindrop(Random.nextFloat(), Random.nextFloat(), if (drizzle) Random.nextInt(12, 20) else Random.nextInt(24, 38))
		}
	}
	val progress by rememberInfiniteTransition(label = "DreamRain").animateFloat(
		initialValue = 0f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing)),
		label = "Rain progress",
	)
	Canvas(Modifier.fillMaxSize().clipToBounds()) {
		val phase = progress
		val length = (if (drizzle) 8.dp else 22.dp).toPx()
		val slant = length * (if (storm) 0.45f else 0.2f)
		val width = (if (drizzle) 0.7.dp else 1.dp).toPx()
		for (drop in drops) {
			val fall = (drop.y + drop.fallsPerLoop * phase) % 1f
			val x = (1.15f * drop.x - 0.15f + fall * 0.13f) * size.width
			val y = fall * (size.height + 2 * length) - length
			drawLine(
				color = Color(0xFFB8D9F5),
				start = Offset(x, y),
				end = Offset(x + slant, y + length),
				strokeWidth = width,
				alpha = if (drizzle) 0.30f else 0.44f,
			)
		}
		if (storm) {
			// A distant glow fades in and out over two seconds.
			val glow = (1f - abs(phase - 0.65f) * 24).coerceAtLeast(0f)
			if (glow > 0f) drawHaze(size.width * 0.72f, 0f, size.width * 0.65f, size.height * 0.55f, 0.18f * glow)
		}
	}
}

private val hazeBrush = Brush.radialGradient(
	colors = listOf(Color(0xFFE4EBF5), Color.Transparent),
	center = Offset.Zero,
	radius = 1f,
)

private fun DrawScope.drawHaze(x: Float, y: Float, radiusX: Float, radiusY: Float, alpha: Float) {
	withTransform({
		translate(x, y)
		scale(radiusX, radiusY, pivot = Offset.Zero)
	}) {
		drawCircle(brush = hazeBrush, radius = 1f, center = Offset.Zero, alpha = alpha)
	}
}
