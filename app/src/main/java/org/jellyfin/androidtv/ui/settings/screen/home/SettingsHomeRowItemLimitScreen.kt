package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

private val HomeRowItemLimitOptions = listOf(10, 20, 30, 40, 50)

@Composable
fun SettingsHomeRowItemLimitScreen() {
	val router = LocalRouter.current
	val userSettingPreferences = koinInject<UserSettingPreferences>()
	var homeRowItemLimit by rememberPreference(userSettingPreferences, UserSettingPreferences.homeRowItemLimit)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.home_prefs).uppercase()) },
				headingContent = { Text(stringResource(R.string.home_row_item_limit)) },
			)
		}

		items(HomeRowItemLimitOptions) { itemLimit ->
			ListButton(
				headingContent = { Text(pluralStringResource(R.plurals.items, itemLimit, itemLimit)) },
				trailingContent = { RadioButton(checked = homeRowItemLimit == itemLimit) },
				onClick = {
					homeRowItemLimit = itemLimit
					router.back()
				},
				modifier = Modifier.focusKey("home_row_item_limit_$itemLimit", initialFocus = homeRowItemLimit == itemLimit)
			)
		}
	}
}
