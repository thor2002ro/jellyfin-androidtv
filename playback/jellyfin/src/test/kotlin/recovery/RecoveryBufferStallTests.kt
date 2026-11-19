package org.jellyfin.playback.jellyfin.recovery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RecoveryBufferStallTests : FunSpec({
	test("buffer growth buys startup time but cannot hide a frozen playhead") {
		for (hasPlayed in listOf(false, true)) {
			shouldRecoverStalledBuffer(0, 5, hasPlayed, checksWithoutPlaybackProgress = 9) shouldBe false
			shouldRecoverStalledBuffer(0, 5, hasPlayed, checksWithoutPlaybackProgress = 10) shouldBe true
			shouldRecoverStalledBuffer(0, 5, hasPlayed, checksWithoutPlaybackProgress = 0) shouldBe false
		}
	}
})
