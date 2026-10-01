package org.jellyfin.playback.media3.exoplayer.subtitle

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.extractor.text.SubtitleParser
import org.jellyfin.playback.media3.exoplayer.AudioPassthroughPolicyAudioSink
import org.jellyfin.playback.media3.exoplayer.StereoDownmixAudioProcessor

@UnstableApi
@OptIn(ExperimentalApi::class)
class SubtitleTimingOffsetRenderersFactory @JvmOverloads constructor(
	context: Context,
	private val offsetState: SubtitleTimingOffsetState,
	private val subtitleParserFactory: SubtitleParser.Factory,
	private val isAudioPassthroughEnabled: (String) -> Boolean = { true },
	private val downmixToStereo: () -> Boolean = { false },
	private val onAudioSinkBufferAttempt: () -> Unit = {},
) : DefaultRenderersFactory(context) {
	override fun buildAudioSink(
		context: Context,
		enableFloatOutput: Boolean,
		enableAudioOutputPlaybackParams: Boolean,
	): AudioSink {
		val downmixProcessor = StereoDownmixAudioProcessor(downmixToStereo)
		return AudioPassthroughPolicyAudioSink(
			DefaultAudioSink.Builder(context)
				// Float output bypasses the PCM processor chain. Keep mixing on the processed path.
				.setEnableFloatOutput(false)
				.setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
				.setAudioProcessors(arrayOf(downmixProcessor))
				.build(),
			downmixToStereo = downmixToStereo,
			downmixProcessor = downmixProcessor,
			onBufferAttempt = onAudioSinkBufferAttempt,
			isPassthroughEnabled = isAudioPassthroughEnabled,
		)
	}

	override fun buildTextRenderers(
		context: Context,
		output: TextOutput,
		outputLooper: Looper,
		extensionRendererMode: Int,
		out: ArrayList<Renderer>,
	) {
		out += TextRenderer(
			output,
			outputLooper,
			SubtitleTimingOffsetDecoderFactory(subtitleParserFactory, offsetState)
		).apply {
			@Suppress("DEPRECATION")
			experimentalSetLegacyDecodingEnabled(true)
		}
	}
}
