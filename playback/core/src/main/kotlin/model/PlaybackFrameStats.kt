package org.jellyfin.playback.core.model

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
) {
	companion object {
		val EMPTY = PlaybackFrameStats(
			droppedFrames = 0,
			corruptedFrames = 0,
		)
	}
}
