package org.jellyfin.playback.media3.exoplayer

import android.media.AudioTrack
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioSink.AudioSinkConfig
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import timber.log.Timber

@OptIn(UnstableApi::class)
internal class AudioPassthroughPolicyAudioSink(
	private val sink: AudioSink,
	private val downmixToStereo: () -> Boolean = { false },
	private val downmixProcessor: StereoDownmixAudioProcessor? = null,
	private val onBufferAttempt: () -> Unit = {},
	private val onOutputFormat: (Format?, Boolean) -> Unit = { _, _ -> },
	private val convertToSupportedSurround: () -> Boolean = { false },
	private val isPassthroughEnabled: (String) -> Boolean,
) : ForwardingAudioSink(sink) {
	private var surround: SurroundEncoding? = null
	private var inputBuffer: ByteBuffer? = null
	private var inputStartPosition = 0
	private var surroundDiscontinuity = false

	override fun configure(audioSinkConfig: AudioSinkConfig) {
		clearSurround()
		if (!downmixToStereo() && convertToSupportedSurround()) {
			surround = sink.createSurroundEncoding(audioSinkConfig, isPassthroughEnabled) { output ->
				sink.configure(
					AudioSinkConfig.Builder(output)
						.setTimeline(audioSinkConfig.timeline)
						.setMediaPeriodId(audioSinkConfig.mediaPeriodId)
						.build(),
				)
			}
			surround?.let { encoding ->
				Timber.i("ExoPlayer local surround conversion output=%s", encoding.format.sampleMimeType)
				onOutputFormat(encoding.format, true)
				return
			}
		}
		downmixProcessor?.setInputFormat(audioSinkConfig.format, audioSinkConfig.outputChannelMapping?.toArray())
		super.configure(audioSinkConfig)
		onOutputFormat(audioSinkConfig.format, false)
	}

	private fun clearSurround() {
		surround?.close()
		surround = null
		inputBuffer = null
		surroundDiscontinuity = false
	}

	override fun flush() {
		surround?.close()
		inputBuffer = null
		surroundDiscontinuity = false
		super.flush()
	}

	override fun reset() {
		clearSurround()
		super.reset()
		onOutputFormat(null, false)
	}

	override fun release() {
		clearSurround()
		super.release()
	}

	override fun handleDiscontinuity() {
		if (surround == null) super.handleDiscontinuity()
		else surroundDiscontinuity = true
	}

	override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
		onBufferAttempt()
		val encoding = surround ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
		if (inputBuffer !== buffer) {
			inputBuffer = buffer
			inputStartPosition = buffer.position()
		}
		while (true) {
			if (!writeSurroundPacket(encoding)) return false
			if (surroundDiscontinuity) {
				surroundDiscontinuity = false
				encoding.close()
				super.handleDiscontinuity()
			}
			if (!buffer.hasRemaining()) {
				inputBuffer = null
				return true
			}
			encoding.queue(buffer, presentationTimeUs, inputStartPosition)
		}
	}

	private fun writeSurroundPacket(encoding: SurroundEncoding): Boolean =
		!encoding.packet.hasRemaining() || super.handleBuffer(encoding.packet, encoding.timeUs, 1)

	override fun playToEndOfStream() {
		val encoding = surround
		if (encoding != null) {
			try {
				if (!writeSurroundPacket(encoding)) return
				encoding.finish()
				if (!writeSurroundPacket(encoding)) return
			} catch (error: AudioSink.InitializationException) {
				throw AudioSink.WriteException(AudioTrack.ERROR, encoding.format, false).apply { initCause(error) }
			}
		}
		super.playToEndOfStream()
	}

	override fun isEnded(): Boolean = surround?.let { !it.hasPcm && !it.packet.hasRemaining() } != false && super.isEnded()

	override fun hasPendingData(): Boolean = surround?.packet?.hasRemaining() == true || super.hasPendingData()

	private fun blocksPassthrough(format: Format): Boolean {
		val mimeType = format.sampleMimeType ?: return false
		return MimeTypes.isAudio(mimeType) && mimeType != MimeTypes.AUDIO_RAW && (downmixToStereo() || !isPassthroughEnabled(mimeType))
	}

	private fun outputFormat(format: Format): Format =
		if (downmixToStereo() && format.sampleMimeType == MimeTypes.AUDIO_RAW && format.channelCount in 3..8) {
			format.buildUpon().setChannelCount(2).setChannelMask(Format.NO_VALUE).build()
		} else format

	override fun supportsFormat(format: Format): Boolean =
		!blocksPassthrough(format) && super.supportsFormat(outputFormat(format))

	override fun getFormatSupport(format: Format): Int = when {
		blocksPassthrough(format) -> AudioSink.SINK_FORMAT_UNSUPPORTED
		else -> super.getFormatSupport(outputFormat(format))
	}

	override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = when {
		downmixToStereo() || blocksPassthrough(format) -> AudioOffloadSupport.DEFAULT_UNSUPPORTED
		else -> super.getFormatOffloadSupport(format)
	}
}
