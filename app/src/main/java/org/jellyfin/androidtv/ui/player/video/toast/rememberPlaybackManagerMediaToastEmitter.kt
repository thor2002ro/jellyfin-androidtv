package org.jellyfin.androidtv.ui.player.video.toast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.isActivePlayback
import kotlin.time.Duration.Companion.milliseconds

private val PauseToastDelay = 750.milliseconds

@Composable
fun rememberPlaybackManagerMediaToastEmitter(
	playbackManager: PlaybackManager,
	mediaToastRegistry: MediaToastRegistry,
) {
	LaunchedEffect(playbackManager) {
		var active = false
		var pauseToastJob: Job? = null
		var pauseToastEmitted = false

		playbackManager.state.playState
			.collect { playState ->
				if (!active) {
					active = playState.isActivePlayback
					return@collect
				}

				when (playState) {
					PlayState.PLAYING,
					PlayState.BUFFERING -> {
						if (pauseToastJob?.isActive == true) {
							pauseToastJob?.cancel()
						} else if (pauseToastEmitted) {
							if (mediaToastRegistry.current.value == null) mediaToastRegistry.emit(R.drawable.ic_play)
						}

						pauseToastJob = null
						pauseToastEmitted = false
					}

					PlayState.PAUSED -> {
						pauseToastJob?.cancel()
						pauseToastEmitted = false
						pauseToastJob = launch {
							delay(PauseToastDelay)
							if (mediaToastRegistry.current.value == null) {
								mediaToastRegistry.emit(R.drawable.ic_pause)
								pauseToastEmitted = true
							}
						}
					}

					else -> Unit
				}
			}
	}
}
