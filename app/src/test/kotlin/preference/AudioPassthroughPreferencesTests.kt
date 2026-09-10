package org.jellyfin.androidtv.preference

import androidx.media3.common.MimeTypes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class AudioPassthroughPreferencesTests : FunSpec({
	test("AC3 preference controls only AC3 passthrough") {
		preferences(ac3 = false).isAudioPassthroughEnabled(MimeTypes.AUDIO_AC3) shouldBe false
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
	every { this@mockk[UserPreferences.ac3Enabled] } returns ac3
	every { this@mockk[UserPreferences.eac3Enabled] } returns eac3
	every { this@mockk[UserPreferences.dtsEnabled] } returns dts
	every { this@mockk[UserPreferences.truehdEnabled] } returns truehd
}
