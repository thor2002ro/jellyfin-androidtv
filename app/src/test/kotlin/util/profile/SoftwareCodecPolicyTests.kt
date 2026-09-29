package org.jellyfin.androidtv.util.profile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.preference.constant.LibMPVDecoder
import org.jellyfin.androidtv.preference.constant.PlaybackBackend

class SoftwareCodecPolicyTests : FunSpec({
	test("MPV automatic decoding advertises hardware codecs only") {
		softwareCodecsEnabledForProfile(
			backend = PlaybackBackend.MPV,
			mpvDecoder = LibMPVDecoder.AUTOMATIC,
			softwareCodecsEnabled = true,
		) shouldBe false
	}

	test("MPV MediaCodec decoding advertises hardware codecs only") {
		softwareCodecsEnabledForProfile(
			backend = PlaybackBackend.MPV,
			mpvDecoder = LibMPVDecoder.MEDIACODEC,
			softwareCodecsEnabled = true,
		) shouldBe false
	}

	test("MPV software decoding honors the software codec preference") {
		softwareCodecsEnabledForProfile(
			backend = PlaybackBackend.MPV,
			mpvDecoder = LibMPVDecoder.SOFTWARE,
			softwareCodecsEnabled = true,
		) shouldBe true
		softwareCodecsEnabledForProfile(
			backend = PlaybackBackend.MPV,
			mpvDecoder = LibMPVDecoder.SOFTWARE,
			softwareCodecsEnabled = false,
		) shouldBe false
	}

	test("other backends preserve the software codec preference") {
		softwareCodecsEnabledForProfile(
			backend = PlaybackBackend.EXOPLAYER,
			mpvDecoder = LibMPVDecoder.AUTOMATIC,
			softwareCodecsEnabled = true,
		) shouldBe true
		softwareCodecsEnabledForProfile(
			backend = PlaybackBackend.LIBVLC,
			mpvDecoder = LibMPVDecoder.AUTOMATIC,
			softwareCodecsEnabled = false,
		) shouldBe false
	}
})
