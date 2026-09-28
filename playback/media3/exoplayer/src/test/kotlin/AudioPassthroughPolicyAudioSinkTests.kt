package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class AudioPassthroughPolicyAudioSinkTests : FunSpec({
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
