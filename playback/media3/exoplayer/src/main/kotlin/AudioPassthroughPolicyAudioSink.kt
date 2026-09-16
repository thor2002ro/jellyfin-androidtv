package org.jellyfin.playback.media3.exoplayer

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioSink.AudioSinkConfig
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
internal class AudioPassthroughPolicyAudioSink(
	sink: AudioSink,
	private val downmixToStereo: () -> Boolean = { false },
	private val downmixProcessor: StereoDownmixAudioProcessor? = null,
	private val onBufferAttempt: () -> Unit = {},
	private val isPassthroughEnabled: (String) -> Boolean,
) : ForwardingAudioSink(sink) {
	override fun configure(audioSinkConfig: AudioSinkConfig) {
		downmixProcessor?.setInputFormat(audioSinkConfig.format, audioSinkConfig.outputChannelMapping?.toArray())
		super.configure(audioSinkConfig)
	}

	override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
		onBufferAttempt()
		return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
	}

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
