package org.jellyfin.androidtv.ui.settings.screen.playback

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.util.MAX_SUBTITLE_LANGUAGE_PREFERENCES
import org.jellyfin.androidtv.util.TrackSelectionServerSync
import org.jellyfin.androidtv.util.toSubtitleLanguagePreferenceString
import org.jellyfin.androidtv.util.toSubtitleLanguagePreferences
import org.jellyfin.androidtv.util.toIso2LanguageCodeOrNull
import org.koin.compose.koinInject
import java.util.Locale

enum class PreferredLanguageType {
	AUDIO,
	SUBTITLE,
}

@Composable
fun SettingsPlaybackPreferredLanguageScreen(type: PreferredLanguageType) {
	if (type == PreferredLanguageType.SUBTITLE) {
		SettingsPlaybackPreferredSubtitleLanguageScreen()
		return
	}

	val router = LocalRouter.current
	val coroutineScope = rememberCoroutineScope()
	val userRepository = koinInject<UserRepository>()
	val trackSelectionServerSync = koinInject<TrackSelectionServerSync>()
	val videoQueueManager = koinInject<VideoQueueManager>()
	val user by userRepository.currentUser.collectAsState()
	val configuration = user?.configuration
	val currentLanguage = when (type) {
		PreferredLanguageType.AUDIO -> configuration?.audioLanguagePreference.toIso2LanguageCodeOrNull()
		PreferredLanguageType.SUBTITLE -> configuration?.subtitleLanguagePreference.toIso2LanguageCodeOrNull()
	}
	val options = remember(currentLanguage) { languageOptions(currentLanguage) }
	val title = when (type) {
		PreferredLanguageType.AUDIO -> R.string.pref_preferred_audio_language
		PreferredLanguageType.SUBTITLE -> R.string.pref_preferred_subtitle_language
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_playback).uppercase()) },
				headingContent = { Text(stringResource(title)) },
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.not_set)) },
				trailingContent = { RadioButton(checked = currentLanguage.isNullOrBlank()) },
				onClick = {
					coroutineScope.launch {
						if (saveLanguage(type, null, configuration?.playDefaultAudioTrack == true, trackSelectionServerSync, videoQueueManager)) {
							router.back()
						}
					}
				}
			)
		}

		items(options) { option ->
			ListButton(
				headingContent = { Text(option.label) },
				captionContent = { Text(option.code.uppercase()) },
				trailingContent = { RadioButton(checked = currentLanguage == option.code) },
				onClick = {
					coroutineScope.launch {
						if (saveLanguage(type, option.code, configuration?.playDefaultAudioTrack == true, trackSelectionServerSync, videoQueueManager)) {
							router.back()
						}
					}
				}
			)
		}
	}
}

private suspend fun saveLanguage(
	type: PreferredLanguageType,
	language: String?,
	playDefaultAudioTrack: Boolean,
	trackSelectionServerSync: TrackSelectionServerSync,
	videoQueueManager: VideoQueueManager,
): Boolean {
	when (type) {
		PreferredLanguageType.AUDIO -> {
			if (!trackSelectionServerSync.savePreferredAudioLanguage(language)) return false
			videoQueueManager.setLastPlayedAudioLanguageIsoCode(language.takeUnless { playDefaultAudioTrack })
			videoQueueManager.setLastPlayedAudioCodec(null)
		}

		PreferredLanguageType.SUBTITLE -> {
			if (!trackSelectionServerSync.savePreferredSubtitleLanguage(language)) return false
			videoQueueManager.setLastPlayedSubtitleLanguageIsoCode(language)
			videoQueueManager.setLastPlayedSubtitleForcedState(false)
			videoQueueManager.setLastPlayedSubtitleCodec(null)
			videoQueueManager.setLastPlayedSubtitleTitle(null)
		}
	}
	return true
}

@Composable
private fun SettingsPlaybackPreferredSubtitleLanguageScreen() {
	val coroutineScope = rememberCoroutineScope()
	val userPreferences = koinInject<UserPreferences>()
	val userRepository = koinInject<UserRepository>()
	val trackSelectionServerSync = koinInject<TrackSelectionServerSync>()
	val videoQueueManager = koinInject<VideoQueueManager>()
	val user by userRepository.currentUser.collectAsState()
	var subtitleLanguagePreferences by rememberPreference(userPreferences, UserPreferences.subtitleLanguagePreferences)
	val selectedLanguages = remember(subtitleLanguagePreferences, user?.configuration?.subtitleLanguagePreference) {
		subtitleLanguagePreferences.toSubtitleLanguagePreferences()
			.ifEmpty { listOfNotNull(user?.configuration?.subtitleLanguagePreference.toIso2LanguageCodeOrNull()) }
	}
	val options = remember(selectedLanguages) { languageOptions(selectedLanguages.firstOrNull()) }

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_playback).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_preferred_subtitle_language)) },
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.not_set)) },
				trailingContent = { Checkbox(checked = selectedLanguages.isEmpty()) },
				onClick = {
					coroutineScope.launch {
						if (saveSubtitleLanguages(emptyList(), trackSelectionServerSync, videoQueueManager)) {
							subtitleLanguagePreferences = ""
						}
					}
				}
			)
		}

		items(options) { option ->
			val order = selectedLanguages.indexOf(option.code)
			val checked = order != -1
			ListButton(
				headingContent = { Text(option.label) },
				captionContent = { Text(if (checked) "${order + 1}. ${option.code.uppercase()}" else option.code.uppercase()) },
				trailingContent = { Checkbox(checked = checked) },
				onClick = {
					val updatedLanguages = when {
						checked -> selectedLanguages - option.code
						selectedLanguages.size >= MAX_SUBTITLE_LANGUAGE_PREFERENCES -> selectedLanguages
						else -> selectedLanguages + option.code
					}
					if (updatedLanguages == selectedLanguages) return@ListButton

					coroutineScope.launch {
						if (saveSubtitleLanguages(updatedLanguages, trackSelectionServerSync, videoQueueManager)) {
							subtitleLanguagePreferences = updatedLanguages.toSubtitleLanguagePreferenceString()
						}
					}
				}
			)
		}
	}
}

private suspend fun saveSubtitleLanguages(
	languages: List<String>,
	trackSelectionServerSync: TrackSelectionServerSync,
	videoQueueManager: VideoQueueManager,
): Boolean {
	val normalized = languages.toSubtitleLanguagePreferences()
	if (!trackSelectionServerSync.savePreferredSubtitleLanguage(normalized.firstOrNull())) return false

	videoQueueManager.setLastPlayedSubtitleLanguageIsoCodes(normalized)
	videoQueueManager.setLastPlayedSubtitleForcedState(false)
	videoQueueManager.setLastPlayedSubtitleCodec(null)
	videoQueueManager.setLastPlayedSubtitleTitle(null)
	return true
}

private data class LanguageOption(
	val code: String,
	val label: String,
)

private fun languageOptions(currentLanguage: String?): List<LanguageOption> {
	val options = Locale.getISOLanguages()
		.mapNotNull { code ->
			val iso2 = code.toIso2LanguageCodeOrNull() ?: return@mapNotNull null
			runCatching {
				val locale = Locale.forLanguageTag(iso2)
				LanguageOption(
					code = iso2,
					label = locale.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) },
				)
			}.getOrNull()
		}
		.distinctBy(LanguageOption::code)
		.sortedBy(LanguageOption::label)

	if (currentLanguage.isNullOrBlank() || options.any { it.code == currentLanguage }) return options
	return listOf(LanguageOption(currentLanguage, languageDisplayName(currentLanguage))) + options
}

internal fun languageDisplayName(language: String): String =
	language.toIso2LanguageCodeOrNull()
		?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
		?.takeIf { it.isNotBlank() }
		?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
		?: language.uppercase()
