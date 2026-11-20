package org.jellyfin.androidtv.ui.settings.screen.screensaver

import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.preference.Preference
import org.jellyfin.preference.PreferenceEnum
import org.koin.compose.koinInject

@Composable
internal fun <T> SettingsScreensaverEffectScreen(
	@StringRes title: Int,
	preference: Preference<T>,
	effects: List<T>,
) where T : Enum<T>, T : PreferenceEnum {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	var selected by rememberPreference(userPreferences, preference, onCommitted = { router.back() })

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_screensaver).uppercase()) },
				headingContent = { Text(stringResource(title)) },
			)
		}

		items(effects) { effect ->
			val checked = effect == selected
			ListButton(
				headingContent = { Text(stringResource(effect.nameRes)) },
				trailingContent = { RadioButton(checked = checked) },
				onClick = { if (checked) router.back() else selected = effect },
				modifier = Modifier.focusKey(effect.name, initialFocus = checked),
			)
		}
	}
}
