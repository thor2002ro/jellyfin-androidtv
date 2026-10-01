package org.jellyfin.androidtv.ui.player.video.toast

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.R
import org.jellyfin.playback.core.model.PlayState

class PlaybackMediaToastStateTests : FunSpec({
	test("initial playback and buffering do not emit duplicate feedback") {
		val state = PlaybackMediaToastState()

		state.update(PlayState.BUFFERING) shouldBe null
		state.update(PlayState.PLAYING) shouldBe null
		state.update(PlayState.BUFFERING) shouldBe null
		state.update(PlayState.PLAYING) shouldBe null
	}

	test("pause and resume emit immediate feedback once") {
		val state = PlaybackMediaToastState()
		state.update(PlayState.PLAYING)

		state.update(PlayState.PAUSED) shouldBe R.drawable.ic_pause
		state.update(PlayState.PAUSED) shouldBe null
		state.update(PlayState.BUFFERING) shouldBe null
		state.update(PlayState.PLAYING) shouldBe R.drawable.ic_play
		state.update(PlayState.PLAYING) shouldBe null
	}

	test("stopped playback does not leak a pending resume notification") {
		val state = PlaybackMediaToastState()
		state.update(PlayState.PLAYING)
		state.update(PlayState.PAUSED)

		state.update(PlayState.STOPPED) shouldBe null
		state.update(PlayState.PLAYING) shouldBe null
	}
})
