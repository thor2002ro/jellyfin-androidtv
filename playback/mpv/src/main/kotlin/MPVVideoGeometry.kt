package org.jellyfin.playback.mpv

import `is`.xyz.mpv.MPVNode
import org.jellyfin.playback.core.model.VideoGeometry
import org.jellyfin.playback.core.model.VideoOutputTransform

internal fun mpvVideoGeometry(parameters: MPVNode): VideoGeometry {
	val frameWidth = parameters.positiveDimension("crop-w") ?: parameters.positiveDimension("w")
	val frameHeight = parameters.positiveDimension("crop-h") ?: parameters.positiveDimension("h")
	if (frameWidth == null || frameHeight == null) return VideoGeometry.EMPTY

	val declaredAspect = parameters.number("aspect")?.validAspect()
	val displayAspect = if (declaredAspect == null) {
		val displayWidth = parameters.number("dw")
		val displayHeight = parameters.number("dh")
		if (displayWidth != null && displayHeight != null && displayWidth > 0.0 && displayHeight > 0.0) {
			(displayWidth / displayHeight).validAspect()
		} else {
			null
		}
	} else {
		declaredAspect
	}

	val rotation = parameters.number("rotate")?.toInt()?.let { ((it % 360) + 360) % 360 } ?: 0
	val quarterTurn = rotation == 90 || rotation == 270
	val orientedWidth = if (quarterTurn) frameHeight else frameWidth
	val orientedHeight = if (quarterTurn) frameWidth else frameHeight
	val orientedAspect = if (quarterTurn) displayAspect?.let { (1.0 / it).validAspect() } else displayAspect

	return VideoGeometry(
		frameWidth = orientedWidth,
		frameHeight = orientedHeight,
		displayAspectRatio = orientedAspect
			?.takeIf { it <= Float.MAX_VALUE.toDouble() }
			?.toFloat(),
	)
}

private fun MPVNode.positiveDimension(key: String): Int? = number(key)
	?.takeIf { it.isFinite() && it > 0.0 && it <= Int.MAX_VALUE.toDouble() }
	?.toInt()
	?.takeIf { it > 0 }

private fun MPVNode.number(key: String): Double? = get(key)?.let { node ->
	node.asDouble() ?: node.asInt()?.toDouble()
}

private fun Double.validAspect(): Double? = takeIf { isFinite() && this > 0.0 }

internal class MPVVideoOutput(
	private val writeAspectRatio: (String) -> Unit,
) {
	private var transform = VideoOutputTransform.NONE

	fun apply(newTransform: VideoOutputTransform) {
		if (newTransform == transform) return
		transform = newTransform
		write()
	}

	fun reapply() = write()

	private fun write() {
		val aspectRatio = transform.aspectRatioOverride
		writeAspectRatio(aspectRatio?.let { "${it.width}:${it.height}" } ?: "no")
	}
}
