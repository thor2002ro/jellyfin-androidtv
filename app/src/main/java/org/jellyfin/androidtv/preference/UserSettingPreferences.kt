package org.jellyfin.androidtv.preference

import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.constant.distinctHomeSections
import org.jellyfin.androidtv.preference.store.DisplayPreferencesStore
import org.jellyfin.preference.booleanPreference
import org.jellyfin.preference.enumPreference
import org.jellyfin.preference.intPreference
import org.jellyfin.sdk.api.client.ApiClient

class UserSettingPreferences(
	api: ApiClient,
) : DisplayPreferencesStore(
	displayPreferencesId = "usersettings",
	api = api,
	app = "emby",
) {
	companion object {
		val skipBackLength = intPreference("skipBackLength", 10_000)
		val skipForwardLength = intPreference("skipForwardLength", 30_000)
		val homeWideCards = booleanPreference("androidtvHomeWideCards", false)
		val homeRowItemLimit = intPreference("androidtvHomeRowItemLimit", 50)
		val homeCombineContinueWatchingNextUp = booleanPreference("androidtvHomeCombineContinueWatchingNextUp", false)
		val homeNextUpRewatching = booleanPreference("androidtvHomeNextUpRewatching", false)
		val homeRecentlyReleased = booleanPreference("androidtvHomeRecentlyReleased", false)

		/** Whether Home episode rows use series artwork. Stored per Jellyfin user. */
		val seriesThumbnailsEnabled = booleanPreference("pref_enable_series_thumbnails", true)

		/** Maximum age in days for Next Up items, or 0 for no cutoff. Stored per Jellyfin user. */
		val homeNextUpMaxDays = intPreference("home_next_up_max_days", 0)

		val homesection0 = enumPreference("homesection0", HomeSectionType.LIBRARY_TILES_SMALL)
		val homesection1 = enumPreference("homesection1", HomeSectionType.RESUME)
		val homesection2 = enumPreference("homesection2", HomeSectionType.RESUME_AUDIO)
		val homesection3 = enumPreference("homesection3", HomeSectionType.NONE)
		val homesection4 = enumPreference("homesection4", HomeSectionType.LIVE_TV)
		val homesection5 = enumPreference("homesection5", HomeSectionType.NEXT_UP)
		val homesection6 = enumPreference("homesection6", HomeSectionType.LATEST_MEDIA)
		val homesection7 = enumPreference("homesection7", HomeSectionType.NONE)
		val homesection8 = enumPreference("homesection8", HomeSectionType.NONE)
		val homesection9 = enumPreference("homesection9", HomeSectionType.NONE)
	}

	val homesections = listOf(
		homesection0,
		homesection1,
		homesection2,
		homesection3,
		homesection4,
		homesection5,
		homesection6,
		homesection7,
		homesection8,
		homesection9,
	)

	val activeHomesections
		get() = homesections
			.map(::get)
			.filterNot { it == HomeSectionType.NONE || it == HomeSectionType.RESUME_BOOK }
			.distinctHomeSections()
}
