package org.jellyfin.androidtv.preference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.preference.constant.PlaybackBackend

class PlaybackUserPreferencesTests : FunSpec({
	test("HDR player defaults to new ExoPlayer") {
		val preferences = UserPreferences.playbackPlayerPreferences(hdr = true)

		preferences.useExternalPlayer.defaultValue shouldBe false
		preferences.playbackRewriteVideoEnabled.defaultValue shouldBe true
		preferences.playbackBackend.defaultValue shouldBe PlaybackBackend.EXOPLAYER
	}
})
