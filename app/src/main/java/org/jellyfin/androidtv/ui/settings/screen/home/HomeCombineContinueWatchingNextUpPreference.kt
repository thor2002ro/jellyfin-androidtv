package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference

@Composable
internal fun HomeCombineContinueWatchingNextUpPreference(userSettingPreferences: UserSettingPreferences) {
	var combineRows by rememberPreference(userSettingPreferences, UserSettingPreferences.homeCombineContinueWatchingNextUp)

	ListButton(
		headingContent = { Text(stringResource(R.string.home_combine_continue_watching_next_up)) },
		captionContent = { Text(stringResource(R.string.home_combine_continue_watching_next_up_description)) },
		trailingContent = { Checkbox(checked = combineRows) },
		onClick = { combineRows = !combineRows },
		modifier = Modifier.focusKey("home_combine_continue_watching_next_up")
	)
}
