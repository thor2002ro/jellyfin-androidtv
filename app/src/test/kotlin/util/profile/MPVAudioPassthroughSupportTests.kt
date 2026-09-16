package org.jellyfin.androidtv.util.profile

import androidx.media3.common.MimeTypes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MPVAudioPassthroughSupportTests : FunSpec({
	test("MPV keeps core formats when only the standard IEC carrier is available") {
		filterMPVPassthroughAudioMimes(
			mimeTypes = setOf(
				MimeTypes.AUDIO_AC3,
				MimeTypes.AUDIO_E_AC3,
				MimeTypes.AUDIO_DTS,
				MimeTypes.AUDIO_DTS_HD,
				MimeTypes.AUDIO_TRUEHD,
			),
			supportedCarrierRates = setOf(48_000),
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
			supportedCarrierRates = setOf(192_000),
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
			supportedCarrierRates = emptySet(),
		).shouldBe(emptySet())
	}
})
