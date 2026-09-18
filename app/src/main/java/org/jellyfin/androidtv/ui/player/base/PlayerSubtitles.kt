package org.jellyfin.androidtv.ui.player.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.util.sdk.toVideoRangeTypeOrNull
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.mediaStreamFlow
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.core.ui.PlayerSubtitleStyle
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.koin.compose.koinInject

@Composable
fun PlayerSubtitles(
	modifier: Modifier = Modifier,
	playbackManager: PlaybackManager = koinInject(),
	userPreferences: UserPreferences = koinInject(),
) {
	val entry by playbackManager.queue.entry.collectAsState()
	val mediaStream = entry?.run { mediaStreamFlow.collectAsState(mediaStream) }?.value
	val videoRangeType = mediaStream?.tracks
		?.filterIsInstance<MediaStreamVideoTrack>()
		?.firstOrNull()
		?.videoRange
		.toVideoRangeTypeOrNull()
	val subtitleStyle = PlayerSubtitleStyle(
		textColor = selectSubtitleTextColor(
			standardTextColor = userPreferences[UserPreferences.subtitlesTextColor],
			hdrTextColor = userPreferences[UserPreferences.subtitlesHdrTextColor],
			videoRangeType = videoRangeType,
		),
		backgroundColor = userPreferences[UserPreferences.subtitlesBackgroundColor].toInt(),
		edgeColor = userPreferences[UserPreferences.subtitleTextStrokeColor].toInt(),
		textWeight = userPreferences[UserPreferences.subtitlesTextWeight],
		textSizeDp = userPreferences[UserPreferences.subtitlesTextSize],
		bottomPaddingFraction = userPreferences[UserPreferences.subtitlesOffsetPosition],
	)

	AndroidView(
		factory = { context ->
			PlayerSubtitleView(context).apply {
				isFocusable = false
				isFocusableInTouchMode = false
				this.subtitleStyle = subtitleStyle
			}
		},
		modifier = modifier,
		update = { view ->
			view.isFocusable = false
			view.isFocusableInTouchMode = false
			view.subtitleStyle = subtitleStyle
			view.playbackManager = playbackManager
		}
	)
}
