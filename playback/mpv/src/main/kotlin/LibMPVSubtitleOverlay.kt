package org.jellyfin.playback.mpv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import `is`.xyz.mpv.MPVNode
import java.nio.ByteBuffer

internal sealed interface LibMPVSubtitleOverlayUpdate {
	data object Unchanged : LibMPVSubtitleOverlayUpdate
	data class Clear(val changeId: Long) : LibMPVSubtitleOverlayUpdate
	data class Frame(
		val changeId: Long,
		val canvasWidth: Int,
		val canvasHeight: Int,
		val x: Int,
		val y: Int,
		val width: Int,
		val height: Int,
		val pixels: ByteArray,
	) : LibMPVSubtitleOverlayUpdate
}

internal fun nativeSubtitleOverlayCommand(previousChangeId: Long, canvasWidth: Int, canvasHeight: Int) = arrayOf(
	"subtitle-overlay-raw",
	previousChangeId.toString(),
	canvasWidth.toString(),
	canvasHeight.toString(),
)

internal fun parseLibMPVSubtitleOverlay(
	node: MPVNode?,
	previousChangeId: Long,
): LibMPVSubtitleOverlayUpdate? {
	val values = node?.asMap() ?: return null
	val changeId = values["change-id"]?.asInt() ?: return null
	val canvasWidth = values.positiveInt("canvas-w") ?: return null
	val canvasHeight = values.positiveInt("canvas-h") ?: return null
	if (changeId == previousChangeId) return LibMPVSubtitleOverlayUpdate.Unchanged

	val width = values.nonNegativeInt("w") ?: return null
	val height = values.nonNegativeInt("h") ?: return null
	if (width == 0 || height == 0) {
		return if (width == 0 && height == 0) LibMPVSubtitleOverlayUpdate.Clear(changeId) else null
	}

	val x = values.nonNegativeInt("x") ?: return null
	val y = values.nonNegativeInt("y") ?: return null
	val stride = values.positiveInt("stride") ?: return null
	val pixels = values["data"]?.asByteArray() ?: return null
	val byteWidth = width.toLong() * 4
	val byteCount = byteWidth * height
	if (values["format"]?.asString() != "bgra" ||
		stride.toLong() != byteWidth ||
		byteCount > Int.MAX_VALUE ||
		pixels.size != byteCount.toInt() ||
		x.toLong() + width > canvasWidth ||
		y.toLong() + height > canvasHeight
	) return null

	return LibMPVSubtitleOverlayUpdate.Frame(
		changeId = changeId,
		canvasWidth = canvasWidth,
		canvasHeight = canvasHeight,
		x = x,
		y = y,
		width = width,
		height = height,
		pixels = pixels,
	)
}

private fun Map<String, MPVNode>.positiveInt(key: String): Int? = this[key]?.asInt()
	?.takeIf { value -> value in 1L..Int.MAX_VALUE.toLong() }
	?.toInt()

private fun Map<String, MPVNode>.nonNegativeInt(key: String): Int? = this[key]?.asInt()
	?.takeIf { value -> value in 0L..Int.MAX_VALUE.toLong() }
	?.toInt()

internal class LibMPVSubtitleOverlayView(context: Context) : View(context) {
	private val paint = Paint().apply { isFilterBitmap = true }
	private var bitmap: Bitmap? = null
	private var frame: LibMPVSubtitleOverlayUpdate.Frame? = null

	init {
		importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
	}

	fun applyUpdate(update: LibMPVSubtitleOverlayUpdate) {
		when (update) {
			LibMPVSubtitleOverlayUpdate.Unchanged -> return
			is LibMPVSubtitleOverlayUpdate.Clear -> clear()
			is LibMPVSubtitleOverlayUpdate.Frame -> {
				val target = bitmap?.takeIf { existing ->
					existing.width == update.width && existing.height == update.height
				} ?: Bitmap.createBitmap(update.width, update.height, Bitmap.Config.ARGB_8888).also {
					bitmap?.recycle()
					bitmap = it
				}
				target.copyPixelsFromBuffer(ByteBuffer.wrap(update.pixels))
				frame = update
				invalidate()
			}
		}
	}

	fun clear() {
		if (frame == null) return
		frame = null
		invalidate()
	}

	override fun onDraw(canvas: Canvas) {
		val currentFrame = frame ?: return
		val currentBitmap = bitmap ?: return
		val scaleX = width.toFloat() / currentFrame.canvasWidth
		val scaleY = height.toFloat() / currentFrame.canvasHeight
		canvas.drawBitmap(
			currentBitmap,
			Rect(0, 0, currentFrame.width, currentFrame.height),
			RectF(
				currentFrame.x * scaleX,
				currentFrame.y * scaleY,
				(currentFrame.x + currentFrame.width) * scaleX,
				(currentFrame.y + currentFrame.height) * scaleY,
			),
			paint,
		)
	}
}
