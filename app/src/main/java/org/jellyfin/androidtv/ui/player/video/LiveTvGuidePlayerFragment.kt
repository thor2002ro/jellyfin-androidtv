package org.jellyfin.androidtv.ui.player.video

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.androidtv.ui.ScreensaverLock
import org.jellyfin.androidtv.ui.base.BaseScreen
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.isLiveTv
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.jellyfin.playback.jellyfin.playsession.PlaySessionService
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import org.koin.android.ext.android.inject
import timber.log.Timber

private val GuidePlayerPreviewWidth = 332.dp
private val GuidePlayerPreviewHeight = 186.dp

class LiveTvGuidePlayerFragment : Fragment(), View.OnKeyListener {
	private val playbackManager by inject<PlaybackManager>()
	private val navigationRepository by inject<NavigationRepository>()
	private val videoQueueManager by inject<VideoQueueManager>()
	private var remoteKeyEventHandler: ((keyCode: Int, event: KeyEvent?) -> Boolean)? = null

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: android.os.Bundle?,
	): View {
		LiveTvGuidePlayback.markStarted()
		return content {
			BaseScreen {
				LiveTvGuidePlayerScreen(
					playbackManager = playbackManager,
					modifier = Modifier.fillMaxSize(),
					onRemoteKeyEventHandlerChanged = { handler -> remoteKeyEventHandler = handler },
					onClosePlayer = ::closePlayer,
				)
			}
		}
	}

	override fun onDestroyView() {
		stopPlayback()
		remoteKeyEventHandler = null
		super.onDestroyView()
	}

	override fun onResume() {
		super.onResume()
		playbackManager.state.unpause()
	}

	override fun onStop() {
		stopPlayback()
		super.onStop()
	}

	override fun onKey(
		v: View?,
		keyCode: Int,
		event: KeyEvent?,
	) = remoteKeyEventHandler?.invoke(keyCode, event) == true

	private fun closePlayer() {
		remoteKeyEventHandler = null
		stopPlayback()
		if (!navigationRepository.goBack()) {
			navigationRepository.reset(Destinations.home)
		}
	}

	private fun stopPlayback() {
		LiveTvGuidePlayback.stop(
			playbackManager = playbackManager,
			videoQueueManager = videoQueueManager,
		)
	}
}

object LiveTvGuidePlayback {
	private var started = false

	fun markStarted() {
		started = true
	}

	fun stopIfStarted(
		playbackManager: PlaybackManager,
		videoQueueManager: VideoQueueManager,
	) {
		if (!started) return
		stop(playbackManager, videoQueueManager)
	}

	fun stop(
		playbackManager: PlaybackManager,
		videoQueueManager: VideoQueueManager,
	) {
		started = false
		Timber.i("Stopping Live TV Guide playback")
		playbackManager.getService<PlaySessionService>()?.sendStopIfActive()
		playbackManager.state.stop()
		playbackManager.queue.clear()
		videoQueueManager.clearVideoQueue()
	}
}

@Composable
private fun LiveTvGuidePlayerScreen(
	playbackManager: PlaybackManager,
	modifier: Modifier = Modifier,
	onRemoteKeyEventHandlerChanged: (((keyCode: Int, event: KeyEvent?) -> Boolean)?) -> Unit,
	onClosePlayer: () -> Unit,
) {
	val userPreferences = koinInject<UserPreferences>()
	val liveTvChannelNavigator = rememberLiveTvChannelNavigator()
	val entry by playbackManager.queue.entry.collectAsState()
	val hasLiveTvPlayback = entry?.isLiveTv == true
	val playbackItem = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value
	val coroutineScope = rememberCoroutineScope()
	val mediaToastRegistry = remember { MediaToastRegistry(coroutineScope) }
	var zoomMode by remember { mutableStateOf(userPreferences[UserPreferences.playerZoomMode]) }
	var guideItem by remember { mutableStateOf<BaseItemDto?>(null) }
	var fullScreen by remember { mutableStateOf(false) }
	var started by remember { mutableStateOf(false) }
	var failed by remember { mutableStateOf(false) }
	val surfaceExpansion by animateFloatAsState(
		targetValue = if (fullScreen) 1f else 0f,
		animationSpec = tween(durationMillis = 260),
		label = "live-tv-guide-player-expansion",
	)
	ScreensaverLock(enabled = true)

	LaunchedEffect(liveTvChannelNavigator) {
		if (hasLiveTvPlayback) {
			started = true
			failed = false
		} else {
			started = liveTvChannelNavigator.startLastOrFirstChannel(playbackManager)
			failed = !started
		}
	}

	LaunchedEffect(hasLiveTvPlayback) {
		if (hasLiveTvPlayback) {
			started = true
			failed = false
		}
	}

	LaunchedEffect(started, playbackItem?.id) {
		if (started && guideItem == null && playbackItem != null) guideItem = playbackItem
	}

	val currentItem = guideItem
	if (started && currentItem != null) {
		BackHandler(enabled = fullScreen) {
			fullScreen = false
		}

		BoxWithConstraints(
			modifier = modifier.background(Color.Black),
		) {
			val playerWidth = GuidePlayerPreviewWidth + (maxWidth - GuidePlayerPreviewWidth) * surfaceExpansion
			val playerHeight = GuidePlayerPreviewHeight + (maxHeight - GuidePlayerPreviewHeight) * surfaceExpansion
			val playerModifier = Modifier
				.offset(x = 0.dp, y = 0.dp)
				.size(playerWidth, playerHeight)
				.padding(if (fullScreen) 0.dp else 1.dp)

			PlayerVideoOutput(
				playbackManager = playbackManager,
				modifier = playerModifier,
			)

			if (fullScreen) {
				VideoPlayerOverlay(
					playbackManager = playbackManager,
					mediaToastRegistry = mediaToastRegistry,
					zoomMode = zoomMode,
					onZoomModeSelected = { zoomMode = it },
					onClosePlayer = { fullScreen = false },
					onRemoteKeyEventHandlerChanged = { handler ->
						onRemoteKeyEventHandlerChanged { keyCode, event ->
							if (
								event?.action == KeyEvent.ACTION_DOWN &&
								event.repeatCount == 0 &&
								(event.keyCode == KeyEvent.KEYCODE_BACK ||
									event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
									event.keyCode == KeyEvent.KEYCODE_BUTTON_B)
							) {
								fullScreen = false
								true
							} else {
								handler?.invoke(keyCode, event) == true
							}
						}
					},
					modifier = Modifier
						.fillMaxSize()
						.graphicsLayer { alpha = surfaceExpansion },
				)
			} else {
				LiveTvGuideOverlay(
					playbackManager = playbackManager,
					currentItem = currentItem,
					onDismiss = onClosePlayer,
					modifier = Modifier.fillMaxSize(),
					showPreview = true,
					fullScreenGuide = true,
					previewOnChannelSelect = true,
					onCurrentChannelSelected = {
						fullScreen = true
					},
					onRemoteKeyEventHandlerChanged = { handler ->
						onRemoteKeyEventHandlerChanged(
							handler?.let { guideHandler ->
								{ _, event -> event?.let(guideHandler) == true }
							}
						)
					},
				)
			}
		}
	} else if (failed) {
		LaunchedEffect(Unit) {
			onClosePlayer()
		}
	}
}
