package org.jellyfin.androidtv.ui.player.video

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.backend.PlayerTrack
import org.jellyfin.playback.core.backend.TrackType
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack

class PlaybackInfoOverlayTests : FunSpec({
	test("selectedTrack follows the active audio stream") {
		val tracks = listOf(
			MediaStreamAudioTrack(1, "aac", 128_000, 2, 48_000, "eng", null),
			MediaStreamAudioTrack(2, "eac3", 640_000, 6, 48_000, "fra", null),
		)
		val selected = PlayerTrack(1, TrackType.AUDIO, null, "fra", "eac3", true, streamIndex = 2)

		tracks.selectedTrack(selected) shouldBe tracks[1]
	}

	test("formatDisplayHdrModes includes every reported HDR mode") {
		formatDisplayHdrModes(setOf(1, 2, 4, 3)) shouldBe "DV; HDR10; HDR10+; HLG"
	}

	test("formatDisplayHdrModes merges Dolby Vision types into the display HDR row") {
		formatDisplayHdrModes(
			supportedHdrTypes = setOf(1, 2, 4, 3),
			dolbyVisionModes = "HEVC P5/P7/P8/EL, AV1 P10",
		) shouldBe "DV HEVC P5/P7/P8/EL, AV1 P10; HDR10; HDR10+; HLG"
	}

	test("formatDisplayHdrModes handles no reported HDR modes") {
		formatDisplayHdrModes(emptySet()) shouldBe "None"
	}

	test("formatDisplayHdrModes preserves unknown reported HDR modes") {
		formatDisplayHdrModes(setOf(99)) shouldBe "Unknown 99"
	}

	test("formatDolbyVisionModes includes every supported Dolby Vision type") {
		formatDolbyVisionModes(
			hevcProfile5 = true,
			hevcProfile7 = true,
			hevcProfile8 = true,
			hevcEnhancementLayer = true,
			av1Profile10 = true,
		) shouldBe "HEVC P5/P7/P8/EL, AV1 P10"
	}

	test("formatDolbyVisionModes handles no supported Dolby Vision types") {
		formatDolbyVisionModes(
			hevcProfile5 = false,
			hevcProfile7 = false,
			hevcProfile8 = false,
			hevcEnhancementLayer = false,
			av1Profile10 = false,
		) shouldBe "None"
	}
})
