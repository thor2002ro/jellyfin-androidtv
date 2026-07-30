package org.jellyfin.androidtv.ui.settings.screen.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.rememberAsyncImagePainter
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.repository.ExternalAppRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.androidtv.preference.playbackPlayerPreferences
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
	val externalPlayerApps = rememberExternalPlayerApps(context, externalAppRepository)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_playback)) },
			)
		}

		item {
			PlayerButton(
				heading = stringResource(R.string.playback_video_player),
				hdr = false,
				externalPlayerApps = externalPlayerApps,
				onClick = { router.push(Routes.PLAYBACK_PLAYER) },
			)
		}

		item {
			PlayerButton(
				heading = stringResource(R.string.playback_hdr_player),
				hdr = true,
				externalPlayerApps = externalPlayerApps,
				onClick = { router.push(Routes.PLAYBACK_HDR_PLAYER) },
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
private fun PlayerButton(
	heading: String,
	hdr: Boolean,
	externalPlayerApps: List<ResolveInfo>,
	onClick: () -> Unit,
) {
	val context = LocalContext.current
	val userPreferences = koinInject<UserPreferences>()
	val externalAppRepository = koinInject<ExternalAppRepository>()
	val playerPreferences = UserPreferences.playbackPlayerPreferences(hdr)
	val useExternalPlayer = userPreferences[playerPreferences.useExternalPlayer]
	val externalPlayerComponentName = userPreferences[playerPreferences.externalPlayerComponentName]
	val playbackRewriteVideoEnabled = userPreferences[playerPreferences.playbackRewriteVideoEnabled]
	val playbackBackend = userPreferences[playerPreferences.playbackBackend]
	val packageManager = context.packageManager
	val externalPlayer = remember(context, hdr, useExternalPlayer, externalPlayerComponentName, externalPlayerApps) {
		externalAppRepository.getCurrentExternalPlayerApp(context, hdr, externalPlayerApps)
	}
	val iconDrawable = remember(externalPlayer, packageManager) { externalPlayer?.loadIcon(packageManager) }
	val externalPlayerName = remember(externalPlayer, packageManager) {
		externalPlayer?.loadLabel(packageManager)?.toString()
	}
	val (iconResource, nameResource) = playerResourceIds(
		useExternalPlayer = useExternalPlayer,
		playbackRewriteVideoEnabled = playbackRewriteVideoEnabled,
		playbackBackend = playbackBackend,
	)
	ListButton(
		leadingContent = {
			PlayerIcon(iconDrawable ?: iconResource)
		},
		headingContent = { Text(heading) },
		captionContent = { Text(externalPlayerName ?: stringResource(nameResource)) },
		onClick = onClick,
	)
}

@Composable
internal fun rememberExternalPlayerApps(
	context: Context,
	externalAppRepository: ExternalAppRepository,
): List<ResolveInfo> {
	val externalPlayerApps = remember { mutableStateOf(externalAppRepository.getExternalPlayerApps(context)) }

	DisposableEffect(context, externalAppRepository) {
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				externalPlayerApps.value = externalAppRepository.getExternalPlayerApps(context)
			}
		}
		val filter = IntentFilter().apply {
			addAction(Intent.ACTION_PACKAGE_ADDED)
			addAction(Intent.ACTION_PACKAGE_CHANGED)
			addAction(Intent.ACTION_PACKAGE_REMOVED)
			addDataScheme("package")
		}
		ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
		onDispose { context.unregisterReceiver(receiver) }
	}

	return externalPlayerApps.value
}

@Composable
internal fun PlayerIcon(model: Any) {
	Image(
		painter = rememberAsyncImagePainter(model),
		contentDescription = null,
		modifier = Modifier
			.size(32.dp)
			.clip(LocalShapes.current.small)
	)
}

internal fun playerResourceIds(
	useExternalPlayer: Boolean,
	playbackRewriteVideoEnabled: Boolean,
	playbackBackend: PlaybackBackend,
) = if (useExternalPlayer) {
	R.drawable.ic_tv_play to R.string.video_player_external
} else if (!playbackRewriteVideoEnabled) {
	R.mipmap.app_icon to R.string.app_name
} else when (playbackBackend) {
	PlaybackBackend.EXOPLAYER -> R.drawable.ic_exoplayer to R.string.playback_backend_exoplayer_name
	PlaybackBackend.LIBVLC -> R.drawable.ic_libvlc to R.string.playback_backend_libvlc_name
	PlaybackBackend.MPV -> R.drawable.ic_mpv to R.string.playback_backend_mpv_name
}

@Composable
internal fun languagePreferenceLabel(language: String?) =
	language.toIso2LanguageCodeOrNull()?.let(::languageDisplayName) ?: stringResource(R.string.not_set)

@Composable
internal fun languagePreferenceLabel(languages: List<String>) =
	languages.takeIf { it.isNotEmpty() }
		?.joinToString(", ") { languageDisplayName(it) }
		?: stringResource(R.string.not_set)
