package org.jellyfin.androidtv.preference

import androidx.media3.common.MimeTypes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.androidtv.preference.constant.BitstreamAudioFormat
import org.jellyfin.androidtv.preference.constant.BitstreamAudioMode

class AudioPassthroughPreferencesTests : FunSpec({
	test("upstream modes control every codec variant") {
		val formats = mapOf(
			BitstreamAudioFormat.AC3 to setOf(MimeTypes.AUDIO_AC3),
			BitstreamAudioFormat.EAC3 to setOf(MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC),
			BitstreamAudioFormat.DTS to setOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_UHD_P2),
			BitstreamAudioFormat.TRUEHD to setOf(MimeTypes.AUDIO_TRUEHD),
		)
		for ((format, mimes) in formats) for (mode in BitstreamAudioMode.entries) {
			val preferences = preferences()
			every { preferences[format.preference] } returns mode
			for (mime in mimes) preferences.isAudioPassthroughEnabled(mime) shouldBe (mode != BitstreamAudioMode.DISABLE)
		}
	}

	test("all bitstream codecs default to upstream auto mode") {
		BitstreamAudioFormat.entries.forEach { it.preference.defaultValue shouldBe BitstreamAudioMode.AUTO }
	}

	test("disabling AC3 blocks both EAC3 variants for every EAC3 mode") {
		val preferences = preferences(ac3 = false)
		for (mode in BitstreamAudioMode.entries) {
			every { preferences[UserPreferences.bitstreamEac3] } returns mode
			preferences.isAudioPassthroughEnabled(MimeTypes.AUDIO_AC3) shouldBe false
			preferences.isAudioPassthroughEnabled(MimeTypes.AUDIO_E_AC3) shouldBe false
			preferences.isAudioPassthroughEnabled(MimeTypes.AUDIO_E_AC3_JOC) shouldBe false
			preferences.isAudioPassthroughEnabled(MimeTypes.AUDIO_DTS) shouldBe true
		}
	}

	test("EAC3 preference covers base and JOC formats") {
		val preferences = preferences(eac3 = false)

		preferences.isAudioPassthroughEnabled(MimeTypes.AUDIO_E_AC3) shouldBe false
		preferences.isAudioPassthroughEnabled(MimeTypes.AUDIO_E_AC3_JOC) shouldBe false
	}

	test("DTS preference covers all Media3 DTS formats") {
		val preferences = preferences(dts = false)

		setOf(
			MimeTypes.AUDIO_DTS,
			MimeTypes.AUDIO_DTS_EXPRESS,
			MimeTypes.AUDIO_DTS_HD,
			MimeTypes.AUDIO_DTS_UHD_P2,
		).all { mime -> !preferences.isAudioPassthroughEnabled(mime) } shouldBe true
	}

	test("unmanaged audio formats retain their normal sink support") {
		preferences(
			ac3 = false,
			eac3 = false,
			dts = false,
			truehd = false,
		).isAudioPassthroughEnabled(MimeTypes.AUDIO_AC4) shouldBe true
	}
})

private fun preferences(
	ac3: Boolean = true,
	eac3: Boolean = true,
	dts: Boolean = true,
	truehd: Boolean = true,
) = mockk<UserPreferences> {
	every { this@mockk[UserPreferences.bitstreamAc3] } returns if (ac3) BitstreamAudioMode.AUTO else BitstreamAudioMode.DISABLE
	every { this@mockk[UserPreferences.bitstreamEac3] } returns if (eac3) BitstreamAudioMode.AUTO else BitstreamAudioMode.DISABLE
	every { this@mockk[UserPreferences.bitstreamDts] } returns if (dts) BitstreamAudioMode.AUTO else BitstreamAudioMode.DISABLE
	every { this@mockk[UserPreferences.bitstreamTrueHd] } returns if (truehd) BitstreamAudioMode.AUTO else BitstreamAudioMode.DISABLE
}
