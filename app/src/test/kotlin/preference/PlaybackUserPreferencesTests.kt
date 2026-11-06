package org.jellyfin.androidtv.preference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.preference.constant.PlaybackBackend

class PlaybackUserPreferencesTests : FunSpec({
	test("HDR player follows video player by default") {
		val preferences = UserPreferences.playbackPlayerPreferences(hdr = true)

		preferences.useExternalPlayer.defaultValue shouldBe false
		preferences.playbackRewriteVideoEnabled.defaultValue shouldBe true
		preferences.playbackBackend.defaultValue shouldBe PlaybackBackend.SAME_VIDEO_PLAYER
	}
})
