package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioSink.AudioSinkConfig
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import android.media.AudioFormat.CHANNEL_OUT_SURROUND
import java.nio.ByteBuffer
import java.nio.ByteOrder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class AudioPassthroughPolicyAudioSinkTests : FunSpec({
	test("sink configuration passes the speaker mask to the PCM mixer") {
		val processor = StereoDownmixAudioProcessor { true }
		val delegate = mockk<AudioSink> {
			every { configure(any<AudioSinkConfig>()) } answers {
				processor.configure(AudioFormat(firstArg<AudioSinkConfig>().format))
				processor.flush()
			}
		}
		val sink = AudioPassthroughPolicyAudioSink(delegate, { true }, processor) { true }
		val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(48000)
			.setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(4).setChannelMask(CHANNEL_OUT_SURROUND).build()
		sink.configure(AudioSinkConfig.Builder(format).build())
		processor.queueInput(ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).apply {
			putShort(0); putShort(0); putShort(10000); putShort(0); flip()
		})
		val output = processor.output
		val left = output.short.toInt()
		(left > 0) shouldBe true
		output.short.toInt() shouldBe left
	}
	test("stereo mixing disables encoded passthrough and offload even when allowed") {
		val sink = AudioPassthroughPolicyAudioSink(supportingAudioSink(), downmixToStereo = { true }) { true }
		val encoded = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).build()
		sink.supportsFormat(encoded) shouldBe false
		sink.getFormatSupport(encoded) shouldBe AudioSink.SINK_FORMAT_UNSUPPORTED
		sink.getFormatOffloadSupport(encoded) shouldBe AudioOffloadSupport.DEFAULT_UNSUPPORTED
	}
	test("stereo-only output accepts multichannel PCM for local mixing") {
		val delegate = mockk<AudioSink> {
			every { getFormatSupport(any()) } answers {
				if (firstArg<Format>().channelCount == 2) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED
			}
			every { supportsFormat(any()) } answers { firstArg<Format>().channelCount == 2 }
		}
		val sink = AudioPassthroughPolicyAudioSink(delegate, downmixToStereo = { true }) { true }
		val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(6).build()
		sink.supportsFormat(pcm) shouldBe true
		sink.getFormatSupport(pcm) shouldBe AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
	}
	test("disabled encoded format is rejected so a decoder can produce PCM") {
		val delegate = supportingAudioSink()
		val sink = AudioPassthroughPolicyAudioSink(delegate) { mime -> mime != MimeTypes.AUDIO_AC3 }
		val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).build()

		sink.supportsFormat(ac3) shouldBe false
		sink.getFormatSupport(ac3) shouldBe AudioSink.SINK_FORMAT_UNSUPPORTED
		sink.getFormatOffloadSupport(ac3) shouldBe AudioOffloadSupport.DEFAULT_UNSUPPORTED
	}

	test("PCM and enabled encoded formats retain sink support") {
		val delegate = supportingAudioSink()
		val sink = AudioPassthroughPolicyAudioSink(delegate) { mime -> mime != MimeTypes.AUDIO_AC3 }
		val pcm = Format.Builder()
			.setSampleMimeType(MimeTypes.AUDIO_RAW)
			.setPcmEncoding(C.ENCODING_PCM_16BIT)
			.build()
		val eac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build()

		sink.supportsFormat(pcm) shouldBe true
		sink.getFormatSupport(pcm) shouldBe AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
		sink.supportsFormat(eac3) shouldBe true
		sink.getFormatSupport(eac3) shouldBe AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
	}

	test("preference changes are read when support is queried") {
		var enabled = false
		val sink = AudioPassthroughPolicyAudioSink(supportingAudioSink()) { enabled }
		val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).build()

		sink.supportsFormat(ac3) shouldBe false
		enabled = true
		sink.supportsFormat(ac3) shouldBe true
	}
})

private fun supportingAudioSink() = mockk<AudioSink> {
	every { supportsFormat(any()) } returns true
	every { getFormatSupport(any()) } returns AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
	every { getFormatOffloadSupport(any()) } returns AudioOffloadSupport.DEFAULT_UNSUPPORTED
}
