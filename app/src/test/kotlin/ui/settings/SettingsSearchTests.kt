package org.jellyfin.androidtv.ui.settings

import org.jellyfin.androidtv.R
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class SettingsSearchTests : FunSpec({
	val settings = listOf(
		SettingsSearchResult(
			route = Routes.PLAYBACK_ADVANCED,
			label = "Advanced playback preferences",
			path = "Settings > Playback",
		),
		SettingsSearchResult(
			route = Routes.CUSTOMIZATION_THEME,
			label = "App theme",
			path = "Settings > Customization",
		),
		SettingsSearchResult(
			route = Routes.PLAYBACK_ADVANCED,
			label = "Refresh rate switching",
			path = "Settings > Playback > Advanced playback preferences",
			focusKey = "refresh_rate_switching",
			initialFocusIndex = 10,
		),
	)

	test("search matches titles and breadcrumbs case insensitively") {
		filterSettingsSearchResults(settings, "advanced preferences") shouldBe listOf(settings[0], settings[2])
		filterSettingsSearchResults(settings, "customization") shouldBe listOf(settings[1])
		filterSettingsSearchResults(settings, "refresh RATE") shouldBe listOf(settings[2])
		filterSettingsSearchResults(settings, "refresh switching") shouldBe listOf(settings[2])
		filterSettingsSearchResults(
			listOf(SettingsSearchResult(Routes.PLAYBACK_MAX_BITRATE, "Max streaming bitrate", "Settings > Playback")),
			"max bitrate",
		).size shouldBe 1
	}

	test("blank search has no results") {
		filterSettingsSearchResults(settings, "  ") shouldBe emptyList()
	}

	test("catalog destinations are registered routes") {
		val searchableRoutes = settingsSearchEntries.map { it.route }
		routes.keys.shouldContain(Routes.SEARCH)
		settingsSearchEntries.forEach { entry -> routes.keys.shouldContain(entry.route) }
		listOf(
			Routes.CUSTOMIZATION_CLOCK,
			Routes.CUSTOMIZATION_WATCHED_INDICATOR,
			Routes.CUSTOMIZATION_BACKDROP,
			Routes.CUSTOMIZATION_SCREENSAVER_TIMEOUT,
			Routes.PLAYBACK_HDR_PLAYER,
			Routes.PLAYBACK_PLAYER_HEADER_LAYOUT,
			Routes.PLAYBACK_RESUME_SUBTRACT_DURATION,
			Routes.PLAYBACK_DEBUG_CAPABILITIES,
		).forEach(searchableRoutes::shouldContain)
	}

	test("inline setting results carry their focus target") {
		settingsSearchEntries.shouldContain(
			SettingsSearchEntry(
				Routes.PLAYBACK_ADVANCED,
				R.string.lbl_refresh_switching,
				listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced),
				"refresh_rate_switching",
				10,
			)
		)
	}
})
