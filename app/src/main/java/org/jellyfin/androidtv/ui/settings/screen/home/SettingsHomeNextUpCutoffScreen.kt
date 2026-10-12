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
import org.jellyfin.androidtv.util.getQuantityString
import org.koin.compose.koinInject

@Suppress("MagicNumber")
private val NextUpMaxDaysOptions = listOf(7, 14, 30, 60, 90, 180, 365)

@Composable
fun getNextUpCutoffOptions(): List<Pair<Int, String>> {
	val context = LocalContext.current
	return buildList {
		add(0 to stringResource(R.string.home_next_up_max_days_disabled))
		NextUpMaxDaysOptions.forEach { days ->
			add(days to context.getQuantityString(R.plurals.days, days))
		}
	}
}

@Composable
fun SettingsHomeNextUpCutoffScreen() {
	val router = LocalRouter.current
	val userSettingPreferences = koinInject<UserSettingPreferences>()
	val customMessageRepository = koinInject<CustomMessageRepository>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val saveGuard = remember { PreferenceSaveGuard() }
	var homeNextUpMaxDays by remember {
		mutableStateOf(userSettingPreferences[UserSettingPreferences.homeNextUpMaxDays])
	}
	val options = getNextUpCutoffOptions()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.home_prefs).uppercase()) },
				headingContent = { Text(stringResource(R.string.home_next_up_max_days)) },
			)
		}

		items(options) { (days, label) ->
			ListButton(
				headingContent = { Text(label) },
				trailingContent = { RadioButton(checked = homeNextUpMaxDays == days) },
				onClick = onClick@{
					if (!saveGuard.tryStart()) return@onClick
					scope.launch {
						try {
							val result = commitPreferenceChange(
								userSettingPreferences,
								UserSettingPreferences.homeNextUpMaxDays,
								days,
							)
							homeNextUpMaxDays = result.value
							if (result.succeeded) {
								customMessageRepository.pushMessage(CustomMessage.RefreshHomeNextUp)
								router.back()
							} else {
								showPreferenceSaveFailure(context)
							}
						} finally {
							saveGuard.finish()
						}
					}
				},
				modifier = Modifier
					.focusKey("cutoff_$days", initialFocus = homeNextUpMaxDays == days)
			)
		}
	}
}
