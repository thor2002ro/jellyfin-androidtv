package org.jellyfin.androidtv.ui.settings.screen.playback

import androidx.media3.common.MimeTypes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.preference.constant.BitstreamAudioFormat
import org.jellyfin.androidtv.preference.managedAudioPassthroughMimeTypes

class AudioPassthroughSupportTests : FunSpec({
	test("pending detection is distinct from an unsupported route") {
		supportedPassthroughAudioFormats(BitstreamAudioFormat.AC3, null) shouldBe null
		supportedPassthroughAudioFormats(BitstreamAudioFormat.AC3, emptySet()) shouldBe emptyList()
	}

	test("every managed passthrough MIME has a UI descriptor") {
		passthroughAudioFormatMimeTypes shouldBe managedAudioPassthroughMimeTypes
	}

	test("fully supported DTS family lists every variant in display order") {
		val supportedMimes = setOf(
			MimeTypes.AUDIO_DTS_UHD_P2,
			MimeTypes.AUDIO_DTS_HD,
			MimeTypes.AUDIO_DTS_EXPRESS,
			MimeTypes.AUDIO_DTS,
		)

		supportedPassthroughAudioFormats(BitstreamAudioFormat.DTS, supportedMimes)
			?.map { format -> format.label } shouldBe listOf("DTS", "DTS Express", "DTS-HD", "DTS-UHD")
	}

	test("DTS support lists only route-supported variants in display order") {
		val supportedMimes = setOf(
			MimeTypes.AUDIO_DTS_HD,
			MimeTypes.AUDIO_DTS,
		)

		supportedPassthroughAudioFormats(BitstreamAudioFormat.DTS, supportedMimes)
			?.map { format -> format.label } shouldBe listOf("DTS", "DTS-HD")
	}

	test("EAC3 JOC support remains distinct from base EAC3") {
		val supportedMimes = setOf(MimeTypes.AUDIO_E_AC3_JOC)

		supportedPassthroughAudioFormats(BitstreamAudioFormat.EAC3, supportedMimes)
			?.map { format -> format.label } shouldBe listOf("E-AC-3 JOC")
	}

	test("unsupported family has no supported formats") {
		supportedPassthroughAudioFormats(BitstreamAudioFormat.TRUEHD, emptySet())?.shouldBeEmpty()
	}
})
