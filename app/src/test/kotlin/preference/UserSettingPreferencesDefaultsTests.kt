package org.jellyfin.androidtv.preference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.sdk.api.client.ApiClient

class UserSettingPreferencesDefaultsTests : FunSpec({
	test("missing per-user Home options use defaults") {
		val preferences = UserSettingPreferences(mockk<ApiClient>(relaxed = true))

		preferences[UserSettingPreferences.seriesThumbnailsEnabled] shouldBe true
		preferences[UserSettingPreferences.homeNextUpMaxDays] shouldBe 0
	}

	test("stored unsupported Home sections are ignored") {
		val preferences = UserSettingPreferences(mockk<ApiClient>(relaxed = true))
		preferences.homesections.forEach { preferences[it] = HomeSectionType.NONE }
		preferences[UserSettingPreferences.homesection0] = HomeSectionType.RESUME_BOOK
		preferences[UserSettingPreferences.homesection1] = HomeSectionType.RESUME

		preferences.activeHomesections shouldBe listOf(HomeSectionType.RESUME)
	}
})
