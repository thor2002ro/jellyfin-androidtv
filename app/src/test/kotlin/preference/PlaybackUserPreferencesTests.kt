package org.jellyfin.androidtv.preference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.androidtv.preference.constant.PlayerHeaderLayout

class PlaybackUserPreferencesTests : FunSpec({
	test("existing display switching choices migrate to independent options") {
		mockkStatic(PreferenceManager::class)
		try {
			val choices = mapOf(
				"DISABLED" to (false to false),
				"SCALE_ON_DEVICE" to (true to false),
				"SCALE_ON_TV" to (true to true),
			)
			for ((oldValue, expected) in choices) {
				val values = mutableMapOf<String, Any>("store_version" to 10, "refresh_rate_switching_behavior" to oldValue)
				val store = mockk<SharedPreferences>()
				val editor = mockk<SharedPreferences.Editor>(relaxed = true)
				every { store.edit() } returns editor
				every { store.getInt(any(), any()) } answers { values[firstArg()] as? Int ?: secondArg() }
				every { store.getString(any(), any()) } answers { values[firstArg()] as? String ?: secondArg() }
				every { store.getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
				every { editor.putBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>(); editor }
				every { editor.putInt(any(), any()) } answers { values[firstArg()] = secondArg<Int>(); editor }
				every { editor.remove(any()) } answers { values.remove(firstArg<String>()); editor }
				every { PreferenceManager.getDefaultSharedPreferences(any<Context>()) } returns store
				val preferences = UserPreferences(mockk())
				preferences[UserPreferences.refreshRateSwitchingEnabled] shouldBe expected.first
				preferences[UserPreferences.resolutionSwitchingEnabled] shouldBe expected.second
				values.containsKey("refresh_rate_switching_behavior") shouldBe false
			}
		} finally {
			unmockkStatic(PreferenceManager::class)
		}
	}
	test("fMP4 HLS is preferred by default") {
		UserPreferences.preferFmp4HlsContainer.defaultValue shouldBe true
	}

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
