package org.jellyfin.androidtv.ui.player.video.toast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.isActivePlayback

@Composable
fun rememberPlaybackManagerMediaToastEmitter(
	playbackManager: PlaybackManager,
	mediaToastRegistry: MediaToastRegistry,
) {
	LaunchedEffect(playbackManager) {
		val toastState = PlaybackMediaToastState()

		playbackManager.state.playState
			.collect { playState ->
				toastState.update(playState)?.let { icon -> mediaToastRegistry.emit(icon) }
			}
	}
}

// Track pause and resume independently from buffering so each user action emits feedback once.
internal class PlaybackMediaToastState {
	private var active = false
	private var paused = false

	fun update(playState: PlayState): Int? {
		if (!active) {
			active = playState.isActivePlayback
			return null
		}

		return when (playState) {
			PlayState.PAUSED -> if (paused) null else {
				paused = true
				R.drawable.ic_pause
			}

			PlayState.PLAYING -> if (paused) {
				paused = false
				R.drawable.ic_play
			} else null

			PlayState.STOPPED,
			PlayState.ERROR -> {
				active = false
				paused = false
				null
			}

			PlayState.BUFFERING -> null
		}
	}
}
