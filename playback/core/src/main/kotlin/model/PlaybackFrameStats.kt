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

data class PlaybackDoviTransformStats(
	val inputPresentation: String,
	val outputPresentation: String,
	val processor: PlaybackDoviTransformProcessor = PlaybackDoviTransformProcessor.LIBDOVI,
)

enum class PlaybackDoviTransformProcessor {
	LIBDOVI,
	FAST_HDR_BASE,
}

data class PlaybackFrameStats(
	val droppedFrames: Int,
	val corruptedFrames: Int,
	val playerName: String? = null,
	val videoDecodedFrames: Int = 0,
	val videoDecoderFps: Float? = null,
	val videoDecoderName: String? = null,
	val videoDecoderType: String? = null,
	val videoCodec: String? = null,
	val videoHdrMode: String? = null,
	val videoSourceFps: Float? = null,
	val videoBitrate: Int? = null,
	val videoRange: String? = null,
	val audioDecoderName: String? = null,
	val audioDecoderType: String? = null,
	val audioCodec: String? = null,
	val audioBitrate: Int? = null,
	val audioChannels: String? = null,
	val audioSampleRate: Int? = null,
	val audioPassthroughSupported: Boolean? = null,
	val bufferedBytes: String? = null,
	val subtitleExtractor: String? = null,
	val subtitleRender: String? = null,
	val subtitleParser: String? = null,
	val extractorFlags: String? = null,
	val libass: PlaybackLibassStats? = null,
	val doviTransform: PlaybackDoviTransformStats? = null,
	val backendDetails: Map<String, String> = emptyMap(),
) {
	companion object {
		val EMPTY = PlaybackFrameStats(
			droppedFrames = 0,
			corruptedFrames = 0,
		)
	}
}

fun Long.formatBufferBytes() = when {
	this >= 1_073_741_824L -> "%.2f GiB".format(this / 1_073_741_824.0)
	this >= 1_048_576L -> "%.2f MiB".format(this / 1_048_576.0)
	this >= 1_024L -> "%.2f KiB".format(this / 1_024.0)
	else -> "$this B"
}
