package org.jellyfin.androidtv.ui.player.video

import androidx.compose.ui.unit.IntSize
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.playback.core.model.VideoAspectRatio
import org.jellyfin.playback.core.model.VideoGeometry
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

private const val HorizontalSnapTolerancePixels = 11
private const val VerticalSnapTolerancePixels = 7
private const val RelativeAspectComparisonTolerance = 0.000001

data class VideoViewport(
	val width: Int,
	val height: Int,
	val aspectRatioOverride: VideoAspectRatio?,
)

internal fun videoZoomStatus(
	zoomMode: ZoomMode,
	viewport: VideoViewport?,
	selectedZoomLabel: String,
	stretchZoomLabel: String,
): String = when {
	zoomMode != ZoomMode.AUTO || viewport == null -> selectedZoomLabel
	viewport.aspectRatioOverride != null -> "$selectedZoomLabel → $stretchZoomLabel"
	else -> selectedZoomLabel
}

fun calculateVideoViewport(
	container: IntSize,
	geometry: VideoGeometry,
	zoomMode: ZoomMode,
): VideoViewport {
	if (container.width <= 0 || container.height <= 0) return VideoViewport(0, 0, null)

	val sourceAspect = geometry.aspectRatioFor(zoomMode).toDouble()
	if (!sourceAspect.isFinite() || sourceAspect <= 0.0) {
		return VideoViewport(container.width, container.height, null)
	}

	val fit = calculateAspectRectangle(container, sourceAspect, fill = false)
		?: return VideoViewport(container.width, container.height, null)
	val autoStretch = zoomMode == ZoomMode.AUTO && fit.isWithinAutoToleranceOf(container)

	val rectangle = when (zoomMode) {
		ZoomMode.AUTO -> if (autoStretch) container else fit
		ZoomMode.FIT -> fit
		ZoomMode.AUTO_CROP -> calculateAspectRectangle(container, sourceAspect, fill = true)
			?: return VideoViewport(container.width, container.height, null)
		ZoomMode.HORIZONTAL_STRETCH -> IntSize(container.width, fit.height)
		ZoomMode.VERTICAL_STRETCH -> IntSize(fit.width, container.height)
		ZoomMode.STRETCH -> container
	}

	val needsOverride = when (zoomMode) {
		ZoomMode.AUTO -> autoStretch && !container.hasAspect(sourceAspect)
		ZoomMode.FIT,
		ZoomMode.AUTO_CROP -> false
		ZoomMode.HORIZONTAL_STRETCH,
		ZoomMode.VERTICAL_STRETCH -> rectangle != fit
		ZoomMode.STRETCH -> !rectangle.hasAspect(sourceAspect)
	}

	return VideoViewport(
		width = rectangle.width,
		height = rectangle.height,
		aspectRatioOverride = if (needsOverride) VideoAspectRatio(rectangle.width, rectangle.height) else null,
	)
}

internal fun VideoGeometry.aspectRatioFor(zoomMode: ZoomMode) =
	if (zoomMode == ZoomMode.FIT) frameAspectRatio else videoAspectRatio

private fun calculateAspectRectangle(
	container: IntSize,
	aspectRatio: Double,
	fill: Boolean,
): IntSize? {
	val containerAspect = container.width.toDouble() / container.height.toDouble()
	val matchContainerWidth = if (fill) aspectRatio < containerAspect else aspectRatio >= containerAspect
	val width = if (matchContainerWidth) container.width else safeRoundDimension(container.height.toDouble() * aspectRatio)
	val height = if (matchContainerWidth) safeRoundDimension(container.width.toDouble() / aspectRatio) else container.height
	if (width == null || height == null) return null
	return IntSize(width, height)
}

private fun safeRoundDimension(value: Double): Int? {
	if (!value.isFinite() || value <= 0.0 || value > Int.MAX_VALUE.toDouble()) return null
	return floor(value + 0.5).toInt().takeIf { it > 0 }
}

private fun IntSize.isWithinAutoToleranceOf(container: IntSize) =
	abs(width.toLong() - container.width.toLong()) <= HorizontalSnapTolerancePixels &&
		abs(height.toLong() - container.height.toLong()) <= VerticalSnapTolerancePixels

private fun IntSize.hasAspect(aspectRatio: Double): Boolean {
	if (width <= 0 || height <= 0) return false
	val outputAspect = width.toDouble() / height.toDouble()
	return abs(outputAspect - aspectRatio) <= RelativeAspectComparisonTolerance * max(outputAspect, aspectRatio)
}
