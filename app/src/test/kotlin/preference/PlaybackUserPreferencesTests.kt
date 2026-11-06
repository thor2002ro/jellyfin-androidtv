package org.jellyfin.androidtv.preference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.androidtv.preference.constant.PlayerHeaderLayout

class PlaybackUserPreferencesTests : FunSpec({
	test("player header defaults to a logo beside its details") {
		UserPreferences.playerHeaderLayout.defaultValue shouldBe PlayerHeaderLayout.LOGO_BESIDE_DETAILS
	}

	test("HDR player follows video player by default") {
		val preferences = UserPreferences.playbackPlayerPreferences(hdr = true)

		preferences.useExternalPlayer.defaultValue shouldBe false
		preferences.playbackRewriteVideoEnabled.defaultValue shouldBe true
		preferences.playbackBackend.defaultValue shouldBe PlaybackBackend.SAME_VIDEO_PLAYER
	}
})
