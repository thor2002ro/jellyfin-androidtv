package org.jellyfin.androidtv.ui.settings.screen.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.preferExoPlayerFfmpeg
import org.jellyfin.androidtv.preference.preferExoPlayerFfmpegAudioForLiveTv
import org.jellyfin.androidtv.preference.preferExoPlayerFfmpegVideo
import org.jellyfin.androidtv.preference.preferExoPlayerFfmpegVideoForLiveTv
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.media3.exoplayer.ExoPlayerBackend
import org.koin.compose.koinInject

@Composable
fun SettingsPlaybackExoPlayerScreen() {
	val userPreferences = koinInject<UserPreferences>()
	val playbackManager = koinInject<PlaybackManager>()
	fun invalidateRendererPreferences() {
		(playbackManager.backend as? ExoPlayerBackend)?.invalidateRendererPreferences()
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_playback_advanced).uppercase()) },
				headingContent = { Text(stringResource(R.string.preference_exoplayer_options)) },
				captionContent = { Text(stringResource(R.string.preference_exoplayer_options_description)) },
			)
		}

		item {
			var preferFfmpegVideoForLiveTv by rememberPreference(userPreferences, UserPreferences.preferExoPlayerFfmpegVideoForLiveTv)

			ListButton(
				headingContent = { Text(stringResource(R.string.prefer_exoplayer_ffmpeg_video_livetv)) },
				captionContent = { Text(stringResource(R.string.prefer_exoplayer_ffmpeg_video_livetv_content)) },
				trailingContent = { Checkbox(checked = preferFfmpegVideoForLiveTv) },
				onClick = {
					preferFfmpegVideoForLiveTv = !preferFfmpegVideoForLiveTv
					invalidateRendererPreferences()
				},
			)
		}

		item {
			var preferFfmpegAudioForLiveTv by rememberPreference(userPreferences, UserPreferences.preferExoPlayerFfmpegAudioForLiveTv)

			ListButton(
				headingContent = { Text(stringResource(R.string.prefer_exoplayer_ffmpeg_audio_livetv)) },
				captionContent = { Text(stringResource(R.string.prefer_exoplayer_ffmpeg_audio_livetv_content)) },
				trailingContent = { Checkbox(checked = preferFfmpegAudioForLiveTv) },
				onClick = {
					preferFfmpegAudioForLiveTv = !preferFfmpegAudioForLiveTv
					invalidateRendererPreferences()
				},
			)
		}

		item {
			var preferExoPlayerFfmpeg by rememberPreference(userPreferences, UserPreferences.preferExoPlayerFfmpeg)

			ListButton(
				headingContent = { Text(stringResource(R.string.prefer_exoplayer_ffmpeg)) },
				trailingContent = { Checkbox(checked = preferExoPlayerFfmpeg) },
				captionContent = { Text(stringResource(R.string.prefer_exoplayer_ffmpeg_content)) },
				onClick = {
					preferExoPlayerFfmpeg = !preferExoPlayerFfmpeg
					invalidateRendererPreferences()
				},
			)
		}

		item {
			var preferExoPlayerFfmpegVideo by rememberPreference(userPreferences, UserPreferences.preferExoPlayerFfmpegVideo)

			ListButton(
				headingContent = { Text(stringResource(R.string.prefer_exoplayer_ffmpeg_video)) },
				trailingContent = { Checkbox(checked = preferExoPlayerFfmpegVideo) },
				captionContent = { Text(stringResource(R.string.prefer_exoplayer_ffmpeg_video_content)) },
				onClick = {
					preferExoPlayerFfmpegVideo = !preferExoPlayerFfmpegVideo
					invalidateRendererPreferences()
				},
			)
		}
	}
}
