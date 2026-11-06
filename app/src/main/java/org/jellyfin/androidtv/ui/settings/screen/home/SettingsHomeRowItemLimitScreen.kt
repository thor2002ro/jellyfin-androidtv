package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.compat.commitPreferenceChange
import org.jellyfin.androidtv.ui.settings.compat.PreferenceSaveGuard
import org.jellyfin.androidtv.ui.settings.compat.showPreferenceSaveFailure
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

private val HomeRowItemLimitOptions = listOf(10, 20, 30, 40, 50)

@Composable
fun SettingsHomeRowItemLimitScreen() {
	val router = LocalRouter.current
	val userSettingPreferences = koinInject<UserSettingPreferences>()
	val customMessageRepository = koinInject<CustomMessageRepository>()
	val scope = rememberCoroutineScope()
	val context = LocalContext.current
	val saveGuard = remember { PreferenceSaveGuard() }
	var homeRowItemLimit by remember {
		mutableStateOf(userSettingPreferences[UserSettingPreferences.homeRowItemLimit])
	}

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
				onClick = onClick@{
					if (!saveGuard.tryStart()) return@onClick
					scope.launch {
						try {
							val result = commitPreferenceChange(
								userSettingPreferences,
								UserSettingPreferences.homeRowItemLimit,
								itemLimit,
							)
							homeRowItemLimit = result.value
							if (result.succeeded) {
								customMessageRepository.pushMessage(CustomMessage.RefreshHomeConfiguration)
								router.back()
							} else {
								showPreferenceSaveFailure(context)
							}
						} finally {
							saveGuard.finish()
						}
					}
				},
				modifier = Modifier.focusKey("home_row_item_limit_$itemLimit", initialFocus = homeRowItemLimit == itemLimit)
			)
		}
	}
}
