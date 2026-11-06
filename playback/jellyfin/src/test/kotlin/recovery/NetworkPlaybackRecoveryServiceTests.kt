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

	test("automatic recovery only handles network and LibVLC errors") {
		isRecoverablePlaybackError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED") shouldBe true
		isRecoverablePlaybackError("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT") shouldBe true
		isRecoverablePlaybackError("LIBVLC_ERROR") shouldBe true
		isRecoverablePlaybackError("ERROR_CODE_DECODING_FAILED") shouldBe false
	}
})
