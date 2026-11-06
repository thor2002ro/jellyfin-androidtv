package org.jellyfin.playback.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SurroundVolumeBoostTests : FunSpec({
	test("boost follows decoded surround and resets for stereo or bitstream output") {
		surroundVolumeBoost(6, 6, true) shouldBe 6
		surroundVolumeBoost(6, 8, true) shouldBe 6
		surroundVolumeBoost(6, 2, true) shouldBe 0
		surroundVolumeBoost(6, 6, false) shouldBe 0
		surroundVolumeBoost(6, null, true) shouldBe 0
		surroundVolumeBoost(0, 6, true) shouldBe 0
		surroundVolumeBoost(-3, 6, true) shouldBe 0
		surroundVolumeBoost(99, 6, true) shouldBe 12
	}
})
