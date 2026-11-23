package org.jellyfin.androidtv.ui.player.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.playback.core.PlaybackManager
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
	val videoIsHdr by playbackManager.videoIsHdr.collectAsState()
	val entry by playbackManager.queue.entry.collectAsState()
	val subtitleStyle = key(entry) {
		PlayerSubtitleStyle(
			textColor = selectSubtitleTextColor(
				standardTextColor = userPreferences[UserPreferences.subtitlesTextColor],
				hdrTextColor = userPreferences[UserPreferences.subtitlesHdrTextColor],
				videoIsHdr = videoIsHdr,
			),
			backgroundColor = userPreferences[UserPreferences.subtitlesBackgroundColor].toInt(),
			edgeColor = userPreferences[UserPreferences.subtitleTextStrokeColor].toInt(),
			textWeight = userPreferences[UserPreferences.subtitlesTextWeight],
			textSizeDp = userPreferences[UserPreferences.subtitlesTextSize],
			bottomPaddingFraction = userPreferences[UserPreferences.subtitlesOffsetPosition],
		)
	}

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
