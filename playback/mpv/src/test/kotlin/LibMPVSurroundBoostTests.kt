package org.jellyfin.playback.mpv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LibMPVSurroundBoostTests : FunSpec({
	test("native gain filter limits peaks without auto leveling or changing bitstreams") {
		mpvSurroundBoostFilter(6, 6, "float") shouldBe
			"@jellyfin-surround-boost:lavfi=[volume=6dB,alimiter=limit=0.95:level=false:latency=true]"
		mpvSurroundBoostFilter(6, 2, "float") shouldBe null
		mpvSurroundBoostFilter(6, 6, "spdif-ac3") shouldBe null
		mpvSurroundBoostFilter(6, 6, null) shouldBe null
		mpvSurroundBoostFilter(0, 6, "float") shouldBe null
	}
	test("boost preference leaves existing native filters and replaygain options intact before PCM detection") {
		val options = LibMPVPlaybackOptions(
			surroundVolumeBoost = 6,
			replayGain = "track",
			customOptions = mapOf("af" to "lavfi=[bass=g=2]"),
		)
		val native = options.runtimeOptions(java.io.File("presets"), true, "gpu-next")
		native["af"] shouldBe "lavfi=[bass=g=2]"
		native["replaygain"] shouldBe "track"
	}
})
