package org.jellyfin.androidtv.util

import kotlin.math.roundToInt

data class BlurHashRequest(
	val blurHash: String,
	val width: Int,
	val height: Int,
)

fun createBlurHashRequest(
	url: String?,
	blurHash: String?,
	isLowRamDevice: Boolean,
	aspectRatio: Double,
	resolution: Int,
): BlurHashRequest? {
	if (url == null) return null
	return createBlurHashDecodeRequest(blurHash, isLowRamDevice, aspectRatio, resolution)
}

fun createBlurHashDecodeRequest(
	blurHash: String?,
	isLowRamDevice: Boolean,
	aspectRatio: Double,
	resolution: Int,
): BlurHashRequest? {
	if (isLowRamDevice) return null
	if (!BlurHashDecoder.isValid(blurHash) || aspectRatio <= 0 || resolution <= 0) return null
	requireNotNull(blurHash)

	return BlurHashRequest(
		blurHash = blurHash,
		width = if (aspectRatio > 1) resolution else (resolution * aspectRatio).roundToInt().coerceAtLeast(1),
		height = if (aspectRatio >= 1) (resolution / aspectRatio).roundToInt().coerceAtLeast(1) else resolution,
	)
}
