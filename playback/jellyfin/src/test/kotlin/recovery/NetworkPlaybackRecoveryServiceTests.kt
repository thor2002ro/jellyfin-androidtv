package org.jellyfin.playback.jellyfin.recovery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.model.PlayState
import kotlin.time.Duration.Companion.seconds

class NetworkPlaybackRecoveryServiceTests : FunSpec({
	test("recovery does not reload resumed or paused playback") {
		hasPlaybackRecovered(PlayState.PLAYING, 2.seconds, 3.seconds) shouldBe true
		hasPlaybackRecovered(PlayState.PAUSED, 2.seconds, 2.seconds) shouldBe true
		hasPlaybackRecovered(PlayState.ERROR, 2.seconds, 2.seconds) shouldBe false
	}
})
