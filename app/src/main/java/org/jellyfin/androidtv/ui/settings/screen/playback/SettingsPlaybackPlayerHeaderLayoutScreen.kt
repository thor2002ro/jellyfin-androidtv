package org.jellyfin.androidtv.ui.settings.screen.playback

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.PlayerHeaderLayout
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsPlaybackPlayerHeaderLayoutScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	var playerHeaderLayout by rememberPreference(userPreferences, UserPreferences.playerHeaderLayout)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_customization).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_player_header_layout)) },
			)
		}

		items(PlayerHeaderLayout.entries) { entry ->
			ListButton(
				headingContent = { Text(stringResource(entry.nameRes)) },
				trailingContent = { RadioButton(checked = playerHeaderLayout == entry) },
				onClick = {
					playerHeaderLayout = entry
					router.back()
				},
				modifier = Modifier.focusKey(
					key = "player_header_layout_${entry.name}",
					initialFocus = playerHeaderLayout == entry,
				)
			)
		}
	}
}
