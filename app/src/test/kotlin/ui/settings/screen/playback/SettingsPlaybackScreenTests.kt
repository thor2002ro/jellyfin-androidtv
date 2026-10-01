package org.jellyfin.androidtv.ui.settings.screen.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.constant.PlaybackBackend

class SettingsPlaybackScreenTests : FunSpec({
	test("external preference remains selected when its app cannot be resolved") {
		playerResourceIds(
			useExternalPlayer = true,
			playbackRewriteVideoEnabled = true,
			playbackBackend = PlaybackBackend.MPV,
		) shouldBe (R.drawable.ic_tv_play to R.string.video_player_external)
	}

	test("same video player overrides stale HDR player preferences") {
		playerResourceIds(
			useExternalPlayer = true,
			playbackRewriteVideoEnabled = false,
			playbackBackend = PlaybackBackend.SAME_VIDEO_PLAYER,
		) shouldBe (R.drawable.ic_tv_play to R.string.playback_hdr_follow_video_player)
	}
})
