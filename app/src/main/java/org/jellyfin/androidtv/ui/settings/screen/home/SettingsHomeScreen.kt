package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.home.effectiveHomeRowItemLimit
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsHomeScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	val userSettingPreferences = koinInject<UserSettingPreferences>()
	val sections = userSettingPreferences.homesections.map(userSettingPreferences::get)
	val activeSections = sections.filterNot { it == HomeSectionType.NONE }

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_customization).uppercase()) },
				headingContent = { Text(stringResource(R.string.home_prefs)) },
			)
		}

		itemsIndexed(activeSections) { index, section ->
			ListButton(
				headingContent = { Text(stringResource(section.nameRes)) },
				captionContent = { Text(stringResource(R.string.home_section_i, index + 1)) },
				onClick = { router.push(Routes.HOME_SECTION, mapOf("index" to index.toString())) },
				modifier = Modifier.focusKey("home_section_$index")
			)
		}

		if (activeSections.size < sections.size) {
			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.home_section_add)) },
					onClick = {
						router.push(Routes.HOME_SECTION, mapOf("index" to activeSections.size.toString()))
					},
					modifier = Modifier.focusKey("home_section_add")
				)
			}
		}

		item {
			ListSection(headingContent = { Text(stringResource(R.string.home_additional_rows)) })
		}

		item { HomeRecentlyReleasedPreference(userSettingPreferences) }

		item { HomeFavoriteVideosPreference(userSettingPreferences) }

		item {
			ListSection(headingContent = { Text(stringResource(R.string.home_appearance)) })
		}

		item {
			HomeWideCardsPreference(userSettingPreferences)
		}

		item {
			HomeRowItemLimitPreference(userSettingPreferences)
		}

		item {
			ListSection(
				headingContent = { Text(stringResource(R.string.lbl_next_up)) },
			)
		}

		item { HomeCombineContinueWatchingNextUpPreference(userSettingPreferences) }

		item {
			HomeNextUpRewatchingPreference(userSettingPreferences)
		}

		item {
			HomeNextUpCutoffPreference(userPreferences)
		}
	}
}

@Composable
private fun HomeWideCardsPreference(userSettingPreferences: UserSettingPreferences) {
	var homeWideCards by rememberPreference(userSettingPreferences, UserSettingPreferences.homeWideCards)

	ListButton(
		headingContent = { Text(stringResource(R.string.home_wide_cards)) },
		captionContent = { Text(stringResource(R.string.home_wide_cards_description)) },
		trailingContent = { Checkbox(checked = homeWideCards) },
		onClick = { homeWideCards = !homeWideCards },
		modifier = Modifier.focusKey("home_wide_cards")
	)
}

@Composable
private fun HomeRecentlyReleasedPreference(userSettingPreferences: UserSettingPreferences) {
	var recentlyReleased by rememberPreference(userSettingPreferences, UserSettingPreferences.homeRecentlyReleased)

	ListButton(
		headingContent = { Text(stringResource(R.string.home_recently_released)) },
		captionContent = { Text(stringResource(R.string.home_recently_released_description)) },
		trailingContent = { Checkbox(checked = recentlyReleased) },
		onClick = { recentlyReleased = !recentlyReleased },
		modifier = Modifier.focusKey("home_recently_released")
	)
}

@Composable
private fun HomeFavoriteVideosPreference(userSettingPreferences: UserSettingPreferences) {
	var favoriteVideos by rememberPreference(userSettingPreferences, UserSettingPreferences.homeFavoriteVideos)

	ListButton(
		headingContent = { Text(stringResource(R.string.home_favorite_videos)) },
		captionContent = { Text(stringResource(R.string.home_favorite_videos_description)) },
		trailingContent = { Checkbox(checked = favoriteVideos) },
		onClick = { favoriteVideos = !favoriteVideos },
		modifier = Modifier.focusKey("home_favorite_videos")
	)
}

@Composable
private fun HomeRowItemLimitPreference(userSettingPreferences: UserSettingPreferences) {
	val router = LocalRouter.current
	val homeRowItemLimit by rememberPreference(userSettingPreferences, UserSettingPreferences.homeRowItemLimit)
	val effectiveItemLimit = effectiveHomeRowItemLimit(homeRowItemLimit, maximum = 50)

	ListButton(
		headingContent = { Text(stringResource(R.string.home_row_item_limit)) },
		captionContent = { Text(pluralStringResource(R.plurals.items, effectiveItemLimit, effectiveItemLimit)) },
		onClick = { router.push(Routes.HOME_ROW_ITEM_LIMIT) },
		modifier = Modifier.focusKey(Routes.HOME_ROW_ITEM_LIMIT)
	)
}

@Composable
private fun HomeNextUpRewatchingPreference(userSettingPreferences: UserSettingPreferences) {
	var includeRewatching by rememberPreference(userSettingPreferences, UserSettingPreferences.homeNextUpRewatching)

	ListButton(
		headingContent = { Text(stringResource(R.string.home_next_up_rewatching)) },
		captionContent = { Text(stringResource(R.string.home_next_up_rewatching_description)) },
		trailingContent = { Checkbox(checked = includeRewatching) },
		onClick = { includeRewatching = !includeRewatching },
		modifier = Modifier.focusKey("home_next_up_rewatching")
	)
}

@Composable
private fun HomeNextUpCutoffPreference(userPreferences: UserPreferences) {
	val router = LocalRouter.current
	val homeNextUpMaxDays by rememberPreference(userPreferences, UserPreferences.homeNextUpMaxDays)
	val options = getNextUpCutoffOptions()
	val selectedCaption = options.firstOrNull { it.first == homeNextUpMaxDays }?.second
		?: stringResource(R.string.home_next_up_max_days_disabled)

	ListButton(
		headingContent = { Text(stringResource(R.string.home_next_up_max_days)) },
		captionContent = { Text(selectedCaption) },
		onClick = { router.push(Routes.HOME_NEXT_UP_CUTOFF) },
		modifier = Modifier.focusKey(Routes.HOME_NEXT_UP_CUTOFF)
	)
}

internal fun compactHomeSections(sections: List<HomeSectionType>): List<HomeSectionType> =
	sections.filterNot { it == HomeSectionType.NONE } + List(
		size = sections.count { it == HomeSectionType.NONE },
		init = { HomeSectionType.NONE },
	)

internal fun moveHomeSection(
	sections: List<HomeSectionType>,
	activeIndex: Int,
	offset: Int,
): List<HomeSectionType> {
	val compacted = compactHomeSections(sections)
	val active = compacted.filterNot { it == HomeSectionType.NONE }.toMutableList()
	val destination = activeIndex + offset
	if (activeIndex !in active.indices || destination !in active.indices) return compacted

	val section = active.removeAt(activeIndex)
	active.add(destination, section)
	return active + List(sections.size - active.size) { HomeSectionType.NONE }
}

internal fun removeHomeSection(
	sections: List<HomeSectionType>,
	activeIndex: Int,
): List<HomeSectionType> {
	val active = sections.filterNot { it == HomeSectionType.NONE }.toMutableList()
	if (activeIndex in active.indices) active.removeAt(activeIndex)
	return active + List(sections.size - active.size) { HomeSectionType.NONE }
}

internal fun setHomeSection(
	sections: List<HomeSectionType>,
	activeIndex: Int,
	section: HomeSectionType,
): List<HomeSectionType> {
	val active = sections.filterNot { it == HomeSectionType.NONE }.toMutableList()
	when {
		activeIndex in active.indices -> active[activeIndex] = section
		activeIndex == active.size && active.size < sections.size -> active.add(section)
		else -> return compactHomeSections(sections)
	}
	return active + List(sections.size - active.size) { HomeSectionType.NONE }
}
