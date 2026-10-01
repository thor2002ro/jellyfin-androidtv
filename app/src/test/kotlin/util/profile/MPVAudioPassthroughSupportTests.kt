package org.jellyfin.androidtv.util.profile

import android.media.AudioFormat
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import androidx.media3.common.MimeTypes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic

class MPVAudioPassthroughSupportTests : FunSpec({
	test("next item reuses carrier support until the output device changes") {
		mockkStatic(::getSupportedPassthroughAudioMimes)
		mockkStatic(SystemClock::class, AudioTrack::class)
		mockkConstructor(AudioAttributes.Builder::class, AudioFormat.Builder::class, AudioTrack::class)
		try {
			val context = mockk<Context>()
			val manager = mockk<AudioManager>()
			var deviceId = 701
			val device = mockk<AudioDeviceInfo> { every { id } answers { deviceId } }
			every { device.type } returns AudioDeviceInfo.TYPE_HDMI
			every { device.encodings } returns intArrayOf(AudioFormat.ENCODING_AC3)
			val pcmDevice = mockk<AudioDeviceInfo> {
				every { id } returns 799
				every { type } returns AudioDeviceInfo.TYPE_USB_DEVICE
				every { encodings } returns intArrayOf(AudioFormat.ENCODING_PCM_16BIT)
			}
			every { context.getSystemService(Context.AUDIO_SERVICE) } returns manager
			every { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf(device)
			var playing = false
			every { manager.isMusicActive } answers { playing }
			var routeMimes = setOf(MimeTypes.AUDIO_AC3)
			every { getSupportedPassthroughAudioMimes(context, any()) } answers { routeMimes }
			var now = 1_000_000L
			every { SystemClock.elapsedRealtime() } answers { now }
			var probes = 0
			var outputAvailable = true
			var highRateAvailable = true
			every { AudioTrack.getMinBufferSize(any(), any(), any()) } answers {
				probes++
				if (outputAvailable && (firstArg<Int>() < 192_000 || highRateAvailable)) 4_096 else AudioTrack.ERROR_BAD_VALUE
			}
			every { anyConstructed<AudioAttributes.Builder>().setUsage(any()) } answers { self as AudioAttributes.Builder }
			every { anyConstructed<AudioAttributes.Builder>().setContentType(any()) } answers { self as AudioAttributes.Builder }
			every { anyConstructed<AudioAttributes.Builder>().build() } returns mockk<AudioAttributes>()
			every { anyConstructed<AudioFormat.Builder>().setEncoding(any()) } answers { self as AudioFormat.Builder }
			every { anyConstructed<AudioFormat.Builder>().setSampleRate(any()) } answers { self as AudioFormat.Builder }
			every { anyConstructed<AudioFormat.Builder>().setChannelMask(any()) } answers { self as AudioFormat.Builder }
			every { anyConstructed<AudioFormat.Builder>().build() } returns mockk<AudioFormat>()
			every { anyConstructed<AudioTrack>().state } returns AudioTrack.STATE_INITIALIZED
			every { anyConstructed<AudioTrack>().release() } just Runs

			getSupportedMPVPassthroughAudioMimes(context, setOf(MimeTypes.AUDIO_AC3)) shouldBe setOf(MimeTypes.AUDIO_AC3)
			probes shouldBe 1
			now += 60_000
			playing = true
			every { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf(device, pcmDevice)
			getSupportedMPVPassthroughAudioMimes(context, setOf(MimeTypes.AUDIO_AC3)) shouldBe setOf(MimeTypes.AUDIO_AC3)
			probes shouldBe 1
			playing = false
			deviceId++
			getSupportedMPVPassthroughAudioMimes(context, setOf(MimeTypes.AUDIO_AC3)) shouldBe setOf(MimeTypes.AUDIO_AC3)
			probes shouldBe 2
			deviceId++
			outputAvailable = false
			getSupportedMPVPassthroughAudioMimes(context, setOf(MimeTypes.AUDIO_AC3)) shouldBe emptySet()
			val failedProbeCount = probes
			outputAvailable = true
			now += 3_000
			getSupportedMPVPassthroughAudioMimes(context, setOf(MimeTypes.AUDIO_AC3)) shouldBe setOf(MimeTypes.AUDIO_AC3)
			probes shouldBe failedProbeCount + 1
			deviceId++
			routeMimes = setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3)
			highRateAvailable = false
			getSupportedMPVPassthroughAudioMimes(context, routeMimes) shouldBe setOf(MimeTypes.AUDIO_AC3)
			val partialProbeCount = probes
			highRateAvailable = true
			now += 3_000
			playing = true
			getSupportedMPVPassthroughAudioMimes(context, routeMimes) shouldBe setOf(MimeTypes.AUDIO_AC3)
			probes shouldBe partialProbeCount
			playing = false
			getSupportedMPVPassthroughAudioMimes(context, routeMimes) shouldBe routeMimes
			probes shouldBe partialProbeCount + 1
		} finally {
			unmockkConstructor(AudioAttributes.Builder::class, AudioFormat.Builder::class, AudioTrack::class)
			unmockkStatic(SystemClock::class, AudioTrack::class)
			unmockkStatic(::getSupportedPassthroughAudioMimes)
		}
	}

	test("carrier probes are shared between codecs and skip unnecessary alternate rates") {
		val probes = mutableListOf<MPVIec61937Carrier>()
		val supported = probeMPVIec61937Carriers(
			setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD),
		) { carrier -> probes.add(carrier); true }
		val expected = setOf(
			MPVIec61937Carrier(48_000, AudioFormat.CHANNEL_OUT_STEREO),
			MPVIec61937Carrier(192_000, AudioFormat.CHANNEL_OUT_STEREO),
			MPVIec61937Carrier(192_000, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND),
		)
		supported shouldBe expected
		probes.toSet() shouldBe expected
		probes.size shouldBe 3
	}

	test("a failed standard carrier is not retried for another codec") {
		val probes = mutableListOf<MPVIec61937Carrier>()
		val supported = probeMPVIec61937Carriers(setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_DTS)) { carrier ->
			probes.add(carrier)
			carrier.sampleRate == 44_100
		}
		supported shouldBe setOf(MPVIec61937Carrier(44_100, AudioFormat.CHANNEL_OUT_STEREO))
		probes.size shouldBe 2
	}

	test("a stereo 192 kHz carrier does not establish TrueHD eight-channel support") {
		filterMPVPassthroughAudioMimes(
			mimeTypes = setOf(MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_TRUEHD),
			supportedCarriers = setOf(MPVIec61937Carrier(192_000, AudioFormat.CHANNEL_OUT_STEREO)),
		) shouldBe setOf(MimeTypes.AUDIO_E_AC3)
	}

	test("MPV keeps core formats when only the standard IEC carrier is available") {
		filterMPVPassthroughAudioMimes(
			mimeTypes = setOf(
				MimeTypes.AUDIO_AC3,
				MimeTypes.AUDIO_E_AC3,
				MimeTypes.AUDIO_DTS,
				MimeTypes.AUDIO_DTS_HD,
				MimeTypes.AUDIO_TRUEHD,
			),
			supportedCarriers = setOf(MPVIec61937Carrier(48_000, AudioFormat.CHANNEL_OUT_STEREO)),
		) shouldBe setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_DTS)
	}

	test("MPV keeps high bitrate formats only with their 192 kHz IEC carrier") {
		filterMPVPassthroughAudioMimes(
			mimeTypes = setOf(
				MimeTypes.AUDIO_E_AC3,
				MimeTypes.AUDIO_E_AC3_JOC,
				MimeTypes.AUDIO_DTS_HD,
				MimeTypes.AUDIO_DTS_UHD_P2,
				MimeTypes.AUDIO_TRUEHD,
			),
			supportedCarriers = setOf(
				MPVIec61937Carrier(192_000, AudioFormat.CHANNEL_OUT_STEREO),
				MPVIec61937Carrier(192_000, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND),
			),
		) shouldBe setOf(
			MimeTypes.AUDIO_E_AC3,
			MimeTypes.AUDIO_E_AC3_JOC,
			MimeTypes.AUDIO_DTS_HD,
			MimeTypes.AUDIO_DTS_UHD_P2,
			MimeTypes.AUDIO_TRUEHD,
		)
	}

	test("MPV rejects passthrough when Android cannot open an IEC carrier") {
		filterMPVPassthroughAudioMimes(
			mimeTypes = setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3),
			supportedCarriers = emptySet(),
		).shouldBe(emptySet())
	}

	test("44.1 kHz core carriers remain available without 48 kHz support") {
		filterMPVPassthroughAudioMimes(
			setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_E_AC3),
			setOf(MPVIec61937Carrier(44_100, AudioFormat.CHANNEL_OUT_STEREO)),
		) shouldBe setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_DTS)
	}

	test("DTS-HD MA and TrueHD do not require stereo carrier support") {
		filterMPVPassthroughAudioMimes(
			setOf(MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_E_AC3),
			setOf(MPVIec61937Carrier(192_000, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND)),
		) shouldBe setOf(MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD)
	}

	test("DTS-HD HRA can use a stereo carrier") {
		filterMPVPassthroughAudioMimes(
			setOf(MimeTypes.AUDIO_DTS_HD),
			setOf(MPVIec61937Carrier(192_000, AudioFormat.CHANNEL_OUT_STEREO)),
		) shouldBe setOf(MimeTypes.AUDIO_DTS_HD)
	}

	test("eight-channel probe buffers account for all carrier channels") {
		MPVIec61937Carrier(192_000, AudioFormat.CHANNEL_OUT_STEREO).frameSizeBytes shouldBe 4
		MPVIec61937Carrier(192_000, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND).frameSizeBytes shouldBe 16
	}
})
