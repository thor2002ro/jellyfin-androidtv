package org.jellyfin.androidtv.util.profile

import android.media.AudioFormat
import androidx.media3.common.MimeTypes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MPVAudioPassthroughSupportTests : FunSpec({
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
