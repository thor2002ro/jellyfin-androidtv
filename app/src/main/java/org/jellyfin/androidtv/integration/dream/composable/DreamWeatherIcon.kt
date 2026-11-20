package org.jellyfin.androidtv.integration.dream.composable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.integration.dream.DreamWeatherCondition

@Composable
internal fun DreamWeatherIcon(condition: DreamWeatherCondition, isDay: Boolean) {
	if (condition == DreamWeatherCondition.UNKNOWN) return
	Canvas(Modifier.size(18.dp)) {
		scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
			when (condition) {
				DreamWeatherCondition.CLEAR -> drawSkyIcon(isDay)
				DreamWeatherCondition.PARTLY_CLOUDY -> {
					scale(0.55f, pivot = Offset.Zero) { drawSkyIcon(isDay) }
					withTransform({
						translate(3f, 4f)
						scale(0.9f, 0.9f, pivot = Offset.Zero)
					}) { drawCloudIcon() }
				}
				DreamWeatherCondition.FOG -> {
					iconLine(4f, 8f, 19f, 8f)
					iconLine(7f, 12f, 22f, 12f)
					iconLine(2f, 16f, 17f, 16f)
				}
				DreamWeatherCondition.CLOUDY, DreamWeatherCondition.DRIZZLE, DreamWeatherCondition.RAIN,
				DreamWeatherCondition.SNOW, DreamWeatherCondition.THUNDERSTORM -> {
					drawCloudIcon()
					when (condition) {
						DreamWeatherCondition.DRIZZLE -> {
							iconLine(8f, 18.5f, 7f, 20f)
							iconLine(16f, 18.5f, 15f, 20f)
						}
						DreamWeatherCondition.RAIN -> for (x in 8..18 step 5) iconLine(x.toFloat(), 18f, x - 2f, 22f)
						DreamWeatherCondition.SNOW -> {
							iconLine(11f, 17.5f, 11f, 23f)
							iconLine(8.3f, 18.8f, 13.7f, 21.7f)
							iconLine(8.3f, 21.7f, 13.7f, 18.8f)
						}
						DreamWeatherCondition.THUNDERSTORM -> drawPath(bolt, Color.White, style = weatherStroke)
						else -> Unit
					}
				}
				DreamWeatherCondition.UNKNOWN -> Unit
			}
		}
	}
}

private val weatherStroke = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private val cloud = Path().apply {
	moveTo(6.5f, 15.5f)
	cubicTo(1.2f, 15.5f, 1.4f, 8.4f, 6.7f, 8.2f)
	cubicTo(7.7f, 2.0f, 16.7f, 2.0f, 17.9f, 8.2f)
	cubicTo(22.7f, 7.8f, 23.4f, 15.5f, 17.7f, 15.5f)
	close()
}

private val moon = Path().apply {
	moveTo(11f, 4f)
	cubicTo(2.2f, 5.1f, 2.1f, 19.6f, 12.8f, 20f)
	cubicTo(16.7f, 20.1f, 19.3f, 17.6f, 20f, 15f)
	cubicTo(13.8f, 17.3f, 7.5f, 10.7f, 11f, 4f)
	close()
}

private val bolt = Path().apply {
	moveTo(12.5f, 16f)
	lineTo(10f, 19.5f)
	lineTo(13f, 19.5f)
	lineTo(11f, 23f)
}

private fun DrawScope.drawCloudIcon() = drawPath(cloud, Color.White, style = weatherStroke)

private fun DrawScope.drawSkyIcon(isDay: Boolean) {
	if (isDay) {
		val center = Offset(12f, 12f)
		drawCircle(Color.White, radius = 4.5f, center = center, style = weatherStroke)
		for (angle in 0 until 360 step 45) {
			rotate(angle.toFloat(), pivot = center) { iconLine(12f, 2f, 12f, 4.5f) }
		}
	} else drawPath(moon, Color.White, style = weatherStroke)
}

private fun DrawScope.iconLine(x1: Float, y1: Float, x2: Float, y2: Float) {
	drawLine(Color.White, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.5f, cap = StrokeCap.Round)
}
