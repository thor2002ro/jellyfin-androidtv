package org.jellyfin.androidtv.ui.settings.screen.playback

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.repository.ExternalAppRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.LocalShapes
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.util.TrackSelectionServerSync
import org.jellyfin.androidtv.util.toSubtitleLanguagePreferences
import org.jellyfin.androidtv.util.toIso2LanguageCodeOrNull
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun SettingsPlaybackScreen() {
	val context = LocalContext.current
	val router = LocalRouter.current
	val coroutineScope = rememberCoroutineScope()
	val externalAppRepository = koinInject<ExternalAppRepository>()
	val userPreferences = koinInject<UserPreferences>()
	val userRepository = koinInject<UserRepository>()
	val videoQueueManager = koinInject<VideoQueueManager>()
	val trackSelectionServerSync = koinInject<TrackSelectionServerSync>()
	val user by userRepository.currentUser.collectAsState()
	val configuration = user?.configuration
	val subtitleLanguagePreferences by rememberPreference(userPreferences, UserPreferences.subtitleLanguagePreferences)
	val subtitleLanguages = remember(subtitleLanguagePreferences, configuration?.subtitleLanguagePreference) {
		subtitleLanguagePreferences.toSubtitleLanguagePreferences()
			.ifEmpty { listOfNotNull(configuration?.subtitleLanguagePreference.toIso2LanguageCodeOrNull()) }
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_playback)) },
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_tv_play), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.playback_video_player)) },
				trailingContent = {
					val iconDrawable = remember(context) {
						externalAppRepository.getCurrentExternalPlayerApp(context)?.loadIcon(context.packageManager)
					}
					Image(
						painter = if (iconDrawable == null) rememberAsyncImagePainter(R.mipmap.app_icon)
						else rememberAsyncImagePainter(iconDrawable),
						contentDescription = null,
						modifier = Modifier
							.size(24.dp)
							.clip(LocalShapes.current.small)
					)
				},
				onClick = { router.push(Routes.PLAYBACK_PLAYER) }
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_select_audio), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_preferred_audio_language)) },
				captionContent = { Text(languagePreferenceLabel(configuration?.audioLanguagePreference)) },
				enabled = configuration != null,
				onClick = { router.push(Routes.PLAYBACK_AUDIO_LANGUAGE) }
			)
		}

		item {
			val playDefaultAudioTrack = configuration?.playDefaultAudioTrack == true
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_select_audio), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_play_default_audio_track)) },
				captionContent = { Text(stringResource(R.string.pref_play_default_audio_track_description)) },
				trailingContent = { Checkbox(checked = playDefaultAudioTrack) },
				enabled = configuration != null,
				onClick = {
					coroutineScope.launch {
						val enabled = !playDefaultAudioTrack
						if (trackSelectionServerSync.savePlayDefaultAudioTrack(enabled)) {
							videoQueueManager.setLastPlayedAudioLanguageIsoCode(
								configuration?.audioLanguagePreference.takeUnless { enabled }
							)
							videoQueueManager.setLastPlayedAudioCodec(null)
						}
					}
				}
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_select_subtitle), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_preferred_subtitle_language)) },
				captionContent = { Text(languagePreferenceLabel(subtitleLanguages)) },
				enabled = configuration != null,
				onClick = { router.push(Routes.PLAYBACK_SUBTITLE_LANGUAGE) }
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_next_up), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_playback_next_up)) },
				onClick = { router.push(Routes.PLAYBACK_NEXT_UP) }
			)
		}

		item {
			var stillWatchingBehavior by rememberPreference(userPreferences, UserPreferences.stillWatchingBehavior)
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_zzz), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_playback_inactivity_prompt)) },
				captionContent = { Text(stringResource(stillWatchingBehavior.nameRes)) },
				onClick = { router.push(Routes.PLAYBACK_INACTIVITY_PROMPT) }
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_trailer), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_playback_prerolls)) },
				onClick = { router.push(Routes.PLAYBACK_PREROLLS) }
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_subtitles), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_customization_subtitles)) },
				onClick = { router.push(Routes.CUSTOMIZATION_SUBTITLES) }
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_clapperboard), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_playback_media_segments)) },
				onClick = { router.push(Routes.PLAYBACK_MEDIA_SEGMENTS) }
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_more), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_playback_advanced)) },
				onClick = { router.push(Routes.PLAYBACK_ADVANCED) }
			)
		}
	}
}

@Composable
internal fun languagePreferenceLabel(language: String?) =
	language.toIso2LanguageCodeOrNull()?.let(::languageDisplayName) ?: stringResource(R.string.not_set)

@Composable
internal fun languagePreferenceLabel(languages: List<String>) =
	languages.takeIf { it.isNotEmpty() }
		?.joinToString(", ") { languageDisplayName(it) }
		?: stringResource(R.string.not_set)
