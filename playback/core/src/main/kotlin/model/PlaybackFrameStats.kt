package org.jellyfin.playback.core.model

data class PlaybackLibassStats(
	val renderCount: Long,
	val changedRenderCount: Long,
	val emptyRenderCount: Long,
	val imageCount: Long,
	val slowRenderCount: Long,
	val maxImageCount: Int,
	val maxBitmapPixels: Long,
	val totalBitmapPixels: Long,
	val atlasUploadPageCount: Long,
	val maxAtlasUploadPageCount: Int,
	val maxAtlasUploadPagePixels: Long,
	val totalAtlasUploadPagePixels: Long,
	val executorTimeoutCount: Long,
	val supersededRequestCount: Long,
	val fps: Double,
	val changedRatio: Double,
	val averageRenderMs: Double,
	val minRenderMs: Double,
	val maxRenderMs: Double,
	val lastRenderMs: Double,
)

data class PlaybackFrameStats(
	val droppedFrames: Int,
	val corruptedFrames: Int,
	val playerName: String? = null,
	val videoDecodedFrames: Int = 0,
	val videoDecoderName: String? = null,
	val videoDecoderType: String? = null,
	val videoCodec: String? = null,
	val videoHdrMode: String? = null,
	val audioDecoderName: String? = null,
	val audioDecoderType: String? = null,
	val audioPassthroughSupported: Boolean? = null,
	val subtitleExtractor: String? = null,
	val subtitleRender: String? = null,
	val subtitleParser: String? = null,
	val subtitlePath: String? = null,
	val extractorFlags: String? = null,
	val libass: PlaybackLibassStats? = null,
) {
	companion object {
		val EMPTY = PlaybackFrameStats(
			droppedFrames = 0,
			corruptedFrames = 0,
		)
	}
}
