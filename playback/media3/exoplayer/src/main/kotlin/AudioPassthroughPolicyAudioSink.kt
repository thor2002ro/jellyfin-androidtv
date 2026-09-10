package org.jellyfin.playback.media3.exoplayer

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

@OptIn(UnstableApi::class)
internal class AudioPassthroughPolicyAudioSink(
	sink: AudioSink,
	private val isPassthroughEnabled: (String) -> Boolean,
) : ForwardingAudioSink(sink) {
	private fun blocksPassthrough(format: Format): Boolean {
		val mimeType = format.sampleMimeType ?: return false
		return MimeTypes.isAudio(mimeType) && mimeType != MimeTypes.AUDIO_RAW && !isPassthroughEnabled(mimeType)
	}

	override fun supportsFormat(format: Format): Boolean =
		!blocksPassthrough(format) && super.supportsFormat(format)

	override fun getFormatSupport(format: Format): Int = when {
		blocksPassthrough(format) -> AudioSink.SINK_FORMAT_UNSUPPORTED
		else -> super.getFormatSupport(format)
	}

	override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = when {
		blocksPassthrough(format) -> AudioOffloadSupport.DEFAULT_UNSUPPORTED
		else -> super.getFormatOffloadSupport(format)
	}
}
