package org.jellyfin.playback.media3.exoplayer

import android.media.AudioFormat.*
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioMixingUtil
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
internal class StereoDownmixAudioProcessor(private val enabled: () -> Boolean) : BaseAudioProcessor() {
	private var matrix: ChannelMixingMatrix? = null
	private var pendingMatrix: ChannelMixingMatrix? = null
	private var inputPositions: List<Int>? = null

	fun setInputFormat(format: Format, channelMapping: IntArray? = null) {
		val positions = channelPositions(format.channelCount, format.channelMask)
		inputPositions = channelMapping?.map { positions[it] } ?: positions
	}

	override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
		if (!enabled() || inputAudioFormat.channelCount <= 2) return AudioFormat.NOT_SET
		if (!AudioMixingUtil.canMix(inputAudioFormat) || inputAudioFormat.channelCount !in 3..8) {
			throw UnhandledAudioFormatException(inputAudioFormat)
		}
		val positions = inputPositions ?: channelPositions(inputAudioFormat.channelCount, Format.NO_VALUE)
		if (positions.size != inputAudioFormat.channelCount) throw UnhandledAudioFormatException(inputAudioFormat)
		pendingMatrix = stereoMatrix(positions)
		return AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
	}

	override fun onFlush() {
		matrix = if (isActive) pendingMatrix else null
	}

	override fun queueInput(inputBuffer: ByteBuffer) {
		val frames = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame
		val output = replaceOutputBuffer(frames * outputAudioFormat.bytesPerFrame)
		AudioMixingUtil.mix(inputBuffer, inputAudioFormat, output, outputAudioFormat, requireNotNull(matrix), frames, false, true)
		output.flip()
	}
}

// Android interleaves PCM channels in ascending speaker-mask bit order.
private fun channelPositions(channels: Int, channelMask: Int): List<Int> {
	val mask = if (channelMask == Format.NO_VALUE) Util.getAudioTrackChannelConfig(channels) else channelMask
	return (0..30).map { 1 shl it }.filter { mask and it != 0 }
}

// Normalize by peak summed gain so correlated full-scale channels do not clip.
private fun stereoMatrix(positions: List<Int>): ChannelMixingMatrix {
	val rows = positions.map { position ->
		when (position) {
			CHANNEL_OUT_FRONT_LEFT -> floatArrayOf(1f, 0f)
			CHANNEL_OUT_FRONT_RIGHT -> floatArrayOf(0f, 1f)
			CHANNEL_OUT_BACK_LEFT, CHANNEL_OUT_SIDE_LEFT, CHANNEL_OUT_FRONT_LEFT_OF_CENTER -> floatArrayOf(0.7071f, 0f)
			CHANNEL_OUT_BACK_RIGHT, CHANNEL_OUT_SIDE_RIGHT, CHANNEL_OUT_FRONT_RIGHT_OF_CENTER -> floatArrayOf(0f, 0.7071f)
			CHANNEL_OUT_FRONT_CENTER -> floatArrayOf(0.7071f, 0.7071f)
			// Centre/rear/LFE and uncommon height speakers contribute to both outputs.
			else -> floatArrayOf(0.5f, 0.5f)
		}
	}
	val peak = maxOf(rows.sumOf { it[0].toDouble() }, rows.sumOf { it[1].toDouble() }).toFloat()
	return ChannelMixingMatrix(positions.size, 2, rows.flatMap { it.toList() }.toFloatArray()).scaleBy(1f / peak)
}
