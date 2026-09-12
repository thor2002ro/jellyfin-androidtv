package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.C
import androidx.media3.common.Format
import android.media.AudioFormat.CHANNEL_OUT_SURROUND
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.assertions.throwables.shouldThrow
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StereoDownmixAudioProcessorTests : FunSpec({
	test("explicit four channel surround mixes centre dialogue equally rather than treating it as quad") {
		val processor = StereoDownmixAudioProcessor { true }
		processor.setInputFormat(Format.Builder().setChannelCount(4).setChannelMask(CHANNEL_OUT_SURROUND).build())
		processor.configure(AudioFormat(48000, 4, C.ENCODING_PCM_16BIT))
		processor.flush()
		processor.queueInput(pcm(0, 0, 10000, 0))
		val output = processor.output
		val left = output.short.toInt()
		left shouldBeInRange 3000..4000
		output.short.toInt() shouldBe left
	}
	test("5.1 centre dialogue reaches both stereo channels") {
		val processor = StereoDownmixAudioProcessor { true }
		processor.configure(AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)).channelCount shouldBe 2
		processor.flush()
		processor.queueInput(pcm(0, 0, 10000, 0, 0, 0))
		val output = processor.output
		val left = output.short.toInt()
		left shouldBeInRange 2000..3000
		output.short.toInt() shouldBe left
	}
	test("7.1 side and rear channels retain their stereo side") {
		for (channel in listOf(4, 5, 6, 7)) {
			val processor = StereoDownmixAudioProcessor { true }
			processor.configure(AudioFormat(48000, 8, C.ENCODING_PCM_16BIT)).channelCount shouldBe 2
			processor.flush()
			processor.queueInput(pcm(*IntArray(8) { if (it == channel) 10000 else 0 }))
			val output = processor.output
			val left = output.short.toInt()
			val right = output.short.toInt()
			if (channel % 2 == 0) { left shouldBeInRange 1000..3000; right shouldBe 0 }
			else { left shouldBe 0; right shouldBeInRange 1000..3000 }
		}
	}
	test("full scale multichannel input does not overflow") {
		for (channels in 3..8) {
			val processor = StereoDownmixAudioProcessor { true }
			processor.configure(AudioFormat(48000, channels, C.ENCODING_PCM_16BIT)).channelCount shouldBe 2
			processor.flush()
			processor.queueInput(pcm(*IntArray(channels) { 32767 }))
			val output = processor.output
			output.short.toInt() shouldBeInRange 32760..32767
			output.short.toInt() shouldBeInRange 32760..32767
		}
	}
	test("disabled mixing and mono or stereo input bypass processing") {
		for (channels in 1..2) StereoDownmixAudioProcessor { true }
			.configure(AudioFormat(48000, channels, C.ENCODING_PCM_16BIT)) shouldBe AudioFormat.NOT_SET
		StereoDownmixAudioProcessor { false }.configure(AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)) shouldBe AudioFormat.NOT_SET
	}
	test("a new stream can disable mixing without retaining the previous matrix") {
		var enabled = true
		val processor = StereoDownmixAudioProcessor { enabled }
		processor.configure(AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)).channelCount shouldBe 2
		processor.flush()
		enabled = false
		processor.configure(AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)) shouldBe AudioFormat.NOT_SET
		processor.flush()
		processor.isActive shouldBe false
	}
	test("unadvertised channel counts fail instead of being silently misrouted") {
		shouldThrow<UnhandledAudioFormatException> {
			StereoDownmixAudioProcessor { true }.configure(AudioFormat(48000, 10, C.ENCODING_PCM_16BIT))
		}
	}
})

private fun pcm(vararg samples: Int): ByteBuffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
	samples.forEach { putShort(it.toShort()) }
	flip()
}
