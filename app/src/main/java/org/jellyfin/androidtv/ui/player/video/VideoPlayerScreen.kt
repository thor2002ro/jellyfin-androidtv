package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.androidtv.ui.ScreensaverLock
import org.jellyfin.androidtv.ui.player.base.PlayerSubtitles
import org.jellyfin.androidtv.ui.player.base.PlayerSurface
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.video.toast.rememberPlaybackManagerMediaToastEmitter
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.koin.compose.koinInject

private const val DefaultVideoAspectRatio = 16f / 9f

@Composable
fun VideoPlayerScreen(
	onRemoteKeyEventHandlerChanged: (((keyCode: Int, event: android.view.KeyEvent?) -> Boolean)?) -> Unit = {},
	onClosePlayer: () -> Unit = {},
) {
	val playbackManager = koinInject<PlaybackManager>()
	val userPreferences = koinInject<UserPreferences>()
	var zoomMode by remember { mutableStateOf(userPreferences[UserPreferences.playerZoomMode]) }

	val backgroundService = koinInject<BackgroundService>()
	LaunchedEffect(backgroundService) {
		backgroundService.clearBackgrounds()
	}

	val playing by remember {
		playbackManager.state.playState.map { it == PlayState.PLAYING }
	}.collectAsState(false)
	ScreensaverLock(
		enabled = playing,
	)

	val videoSize by playbackManager.state.videoSize.collectAsState()
	val aspectRatio = videoSize.aspectRatio.takeIf { !it.isNaN() && it > 0f } ?: DefaultVideoAspectRatio

	val coroutineScope = rememberCoroutineScope()
	val mediaToastRegistry = remember { MediaToastRegistry(coroutineScope) }
	rememberPlaybackManagerMediaToastEmitter(playbackManager, mediaToastRegistry)

	BoxWithConstraints(
		modifier = Modifier
			.background(Color.Black)
			.fillMaxSize()
			.clipToBounds()
	) {
		val viewportSize = remember(maxWidth, maxHeight, aspectRatio, zoomMode) {
			calculateVideoViewportSize(maxWidth, maxHeight, aspectRatio, zoomMode)
		}
		val videoModifier = Modifier
			.requiredSize(viewportSize.width, viewportSize.height)
			.align(Alignment.Center)

		PlayerSurface(
			playbackManager = playbackManager,
			modifier = videoModifier
		)

		PlayerSubtitles(
			playbackManager = playbackManager,
			modifier = videoModifier
		)

		VideoPlayerOverlay(
			playbackManager = playbackManager,
			mediaToastRegistry = mediaToastRegistry,
			zoomMode = zoomMode,
			onZoomModeSelected = { zoomMode = it },
			onRemoteKeyEventHandlerChanged = onRemoteKeyEventHandlerChanged,
			onClosePlayer = onClosePlayer,
		)
	}
}

private data class VideoViewportSize(
	val width: Dp,
	val height: Dp,
)

private fun calculateVideoViewportSize(
	containerWidth: Dp,
	containerHeight: Dp,
	videoAspectRatio: Float,
	zoomMode: ZoomMode,
): VideoViewportSize {
	if (containerWidth <= 0.dp || containerHeight <= 0.dp || videoAspectRatio <= 0f) {
		return VideoViewportSize(containerWidth, containerHeight)
	}

	if (zoomMode == ZoomMode.STRETCH) {
		return VideoViewportSize(containerWidth, containerHeight)
	}

	val containerAspectRatio = containerWidth.value / containerHeight.value
	val matchContainerWidth = when (zoomMode) {
		ZoomMode.FIT -> videoAspectRatio >= containerAspectRatio
		ZoomMode.AUTO_CROP -> videoAspectRatio < containerAspectRatio
		ZoomMode.STRETCH -> true
	}

	val width = if (matchContainerWidth) containerWidth else (containerHeight.value * videoAspectRatio).dp
	val height = if (matchContainerWidth) (containerWidth.value / videoAspectRatio).dp else containerHeight

	return VideoViewportSize(width, height)
}
