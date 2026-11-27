package org.jellyfin.playback.media3.exoplayer

import android.media.AudioFormat
import android.media.AudioTrack
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.ffmpeg.FfmpegSurroundEncoder
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(UnstableApi::class)
internal class SurroundEncoding(
	val format: Format,
	private val inputLayout: Long,
	private val outputLayout: Long,
	inputChannels: Int,
) : AutoCloseable {
	private val bytesPerSampleFrame = inputChannels * Short.SIZE_BYTES
	private val pcm = ByteBuffer.allocateDirect(FfmpegSurroundEncoder.SAMPLES_PER_FRAME * bytesPerSampleFrame)
	val packet: ByteBuffer = ByteBuffer.allocateDirect(FfmpegSurroundEncoder.MAX_PACKET_BYTES)
		.order(ByteOrder.LITTLE_ENDIAN).apply { limit(0) }
	private var encoder: FfmpegSurroundEncoder? = openEncoder()
	var timeUs = C.TIME_UNSET
		private set
	val hasPcm get() = pcm.position() != 0

	private fun openEncoder() = FfmpegSurroundEncoder(
		format.sampleMimeType == MimeTypes.AUDIO_E_AC3, format.sampleRate, inputLayout, outputLayout,
	)

	fun queue(input: ByteBuffer, presentationTimeUs: Long, startPosition: Int) {
		if (!hasPcm) {
			val samplesOffset = (input.position() - startPosition) / bytesPerSampleFrame
			timeUs = presentationTimeUs + samplesOffset * C.MICROS_PER_SECOND / format.sampleRate
		}
		val end = input.position() + minOf(pcm.remaining(), input.remaining())
		val originalLimit = input.limit()
		input.limit(end)
		pcm.put(input)
		input.limit(originalLimit)
		if (!pcm.hasRemaining()) encode()
	}

	fun finish() {
		if (!hasPcm) return
		while (pcm.hasRemaining()) pcm.put(0)
		encode()
	}

	private fun encode() {
		val activeEncoder = encoder ?: try {
			openEncoder().also { encoder = it }
		} catch (error: IllegalArgumentException) {
			throw AudioSink.InitializationException(
				"Unable to reopen FFmpeg surround encoder", AudioTrack.STATE_UNINITIALIZED, format, false, error,
			)
		}
		val size = activeEncoder.encode(pcm, packet)
		if (size <= 0) {
			throw AudioSink.InitializationException(
				"FFmpeg surround encoder returned $size", AudioTrack.STATE_UNINITIALIZED, format, false, null,
			)
		}
		pcm.clear()
		packet.position(0)
		packet.limit(size)
	}

	override fun close() {
		encoder?.close()
		encoder = null
		pcm.clear()
		packet.position(0)
		packet.limit(0)
		timeUs = C.TIME_UNSET
	}
}

@OptIn(UnstableApi::class)
internal fun AudioSink.createSurroundEncoding(
	config: AudioSink.AudioSinkConfig,
	isEnabled: (String) -> Boolean,
	configureOutput: (Format) -> Unit,
): SurroundEncoding? {
	val input = config.format
	if (input.sampleMimeType != MimeTypes.AUDIO_RAW || input.pcmEncoding != C.ENCODING_PCM_16BIT ||
		input.channelCount !in 3..8 || input.sampleRate !in listOf(32_000, 44_100, 48_000) ||
		input.encoderDelay != 0 || input.encoderPadding != 0 || config.outputChannelMapping != null
	) return null

	val inputMask = input.channelMask.takeUnless { it == Format.NO_VALUE }
		?: Util.getAudioTrackChannelConfig(input.channelCount)
	if (Integer.bitCount(inputMask) != input.channelCount) return null
	val outputMask = if (input.channelCount <= 6) inputMask else AudioFormat.CHANNEL_OUT_5POINT1
	for (mimeType in listOf(MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3)) {
		if (!isEnabled(mimeType)) continue
		val output = Format.Builder()
			.setSampleMimeType(mimeType)
			.setSampleRate(input.sampleRate)
			.setChannelCount(Integer.bitCount(outputMask))
			.build()
		if (!supportsFormat(output) || !FfmpegSurroundEncoder.isAvailable(mimeType == MimeTypes.AUDIO_E_AC3)) continue
		val encoding = try {
			// Android position masks start two bits above FFmpeg's standard channel positions.
			SurroundEncoding(output, (inputMask ushr 2).toLong(), (outputMask ushr 2).toLong(), input.channelCount)
		} catch (_: IllegalArgumentException) {
			continue
		}
		try {
			configureOutput(output)
			return encoding
		} catch (_: AudioSink.ConfigurationException) {
			encoding.close()
		}
	}
	return null
}
