package org.jellyfin.playback.libvlc

import org.jellyfin.playback.core.model.VideoGeometry
import org.jellyfin.playback.core.model.VideoOutputTransform

internal fun libVLCVideoGeometry(
	width: Int,
	height: Int,
	visibleWidth: Int,
	visibleHeight: Int,
	sarNum: Int,
	sarDen: Int,
): VideoGeometry {
	val useVisibleDimensions = visibleWidth > 0 && visibleHeight > 0
	val frameWidth = if (useVisibleDimensions) visibleWidth else width
	val frameHeight = if (useVisibleDimensions) visibleHeight else height
	if (frameWidth <= 0 || frameHeight <= 0) return VideoGeometry.EMPTY

	val displayAspect = if (sarNum > 0 && sarDen > 0) {
		(frameWidth.toDouble() * sarNum.toDouble() / frameHeight.toDouble() / sarDen.toDouble())
			.takeIf { it.isFinite() && it > 0.0 && it <= Float.MAX_VALUE.toDouble() }
			?.toFloat()
	} else {
		null
	}

	return VideoGeometry(frameWidth, frameHeight, displayAspect)
}

internal class LibVLCVideoOutput(
	private val writeAspectRatio: (String?) -> Unit,
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
		writeAspectRatio(aspectRatio?.let { "${it.width}:${it.height}" })
	}
}
