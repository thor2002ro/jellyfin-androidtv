package org.jellyfin.androidtv.ui.settings.screen.playback

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.BitstreamAudioFormat
import org.jellyfin.androidtv.preference.constant.BitstreamAudioMode
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
fun SettingsPlaybackBitstreamAudioScreen(
	format: BitstreamAudioFormat,
) {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	var mode by rememberPreference(userPreferences, format.preference)
	val ac3Mode by rememberPreference(userPreferences, UserPreferences.bitstreamAc3)
	val supportedPassthroughMimes = rememberSupportedPassthroughAudioMimes()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_audio).uppercase()) },
				headingContent = { Text(stringResource(format.nameRes)) },
				captionContent = { AudioPassthroughSupportCaption(supportedPassthroughAudioFormats(format, supportedPassthroughMimes)) },
			)
		}

		items(BitstreamAudioMode.entries) { entry ->
			ListButton(
				headingContent = { Text(stringResource(entry.nameRes)) },
				trailingContent = { RadioButton(checked = mode == entry) },
				enabled = format != BitstreamAudioFormat.EAC3 || ac3Mode != BitstreamAudioMode.DISABLE || entry == BitstreamAudioMode.DISABLE,
				onClick = {
					mode = entry
					if (format == BitstreamAudioFormat.AC3 && entry == BitstreamAudioMode.DISABLE) {
						userPreferences[UserPreferences.bitstreamEac3] = BitstreamAudioMode.DISABLE
					}
					router.back()
				},
				modifier = Modifier.focusKey("bitstream_audio_${format.name}_${entry.name}", initialFocus = mode == entry),
			)
		}
	}
}
