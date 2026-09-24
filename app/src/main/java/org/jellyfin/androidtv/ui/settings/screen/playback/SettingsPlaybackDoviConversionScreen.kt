package org.jellyfin.androidtv.ui.settings.screen.playback

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.DoviCompatibilityMode
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsPlaybackDoviCompatibilityScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	var mode by rememberPreference(userPreferences, UserPreferences.doviCompatibilityMode)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_video).uppercase()) },
				headingContent = { Text(stringResource(R.string.dovi_compatibility_title)) },
				captionContent = { Text(stringResource(R.string.dovi_compatibility_description)) },
			)
		}

		items(DoviCompatibilityMode.entries) { entry ->
			ListButton(
				headingContent = { Text(stringResource(entry.nameRes)) },
				captionContent = if (entry == DoviCompatibilityMode.FAST_HDR) {
					{ Text(stringResource(R.string.dovi_compatibility_fast_hdr_description)) }
				} else null,
				trailingContent = { RadioButton(checked = mode == entry) },
				onClick = {
					mode = entry
					router.back()
				},
			)
		}
	}
}
