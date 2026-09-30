package org.jellyfin.androidtv.ui.player.video

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.composable.modifier.overscan
import org.jellyfin.androidtv.ui.player.base.PlayerOverlayLayout
import org.jellyfin.androidtv.ui.player.base.rememberPlayerOverlayVisibility
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.base.toast.MediaToasts
import org.jellyfin.androidtv.util.sdk.isLiveTv
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val CenterLongPressDuration = 2.seconds
private val SeekOverlayDuration = 2.seconds
private val DpadSeekScrubDuration = 750.milliseconds
private val DpadSeekCommitDelay = 500.milliseconds
private val DpadSeekToastDuration = 1500.milliseconds
private val BackToStopTimeout = 2.seconds

@Composable
fun VideoPlayerOverlay(
	modifier: Modifier = Modifier,
	playbackManager: PlaybackManager = koinInject(),
	mediaToastRegistry: MediaToastRegistry,
	zoomMode: ZoomMode,
	onZoomModeSelected: (ZoomMode) -> Unit,
	onRemoteKeyEventHandlerChanged: (((keyCode: Int, event: KeyEvent?) -> Boolean)?) -> Unit = {},
	onClosePlayer: () -> Unit = {},
) {
	val playState by playbackManager.state.playState.collectAsState()
	var pausedOverlayDismissed by remember { mutableStateOf(false) }
	var showPlaybackInfo by remember { mutableStateOf(false) }
	var skipPromptTarget by remember { mutableStateOf<Duration?>(null) }
	var overlayWakeKeyCode by remember { mutableStateOf<Int?>(null) }
	var skipPromptKeyCode by remember { mutableStateOf<Int?>(null) }
	var centerShortcutKeyCode by remember { mutableStateOf<Int?>(null) }
	var seekPauseOverlaySuppressed by remember { mutableStateOf(false) }
	var seekOverlayVisible by remember { mutableStateOf(false) }
	var seekOverlayJob by remember { mutableStateOf<Job?>(null) }
	var pendingSeekPosition by remember { mutableStateOf<Duration?>(null) }
	var pendingSeekCommitJob by remember { mutableStateOf<Job?>(null) }
	var seekScrubJob by remember { mutableStateOf<Job?>(null) }
	var backToStopDeadline by remember { mutableStateOf(0L) }
	var backToStopKeyCode by remember { mutableStateOf<Int?>(null) }
	var centerLongPressTriggered by remember { mutableStateOf(false) }
	var centerLongPressJob by remember { mutableStateOf<Job?>(null) }
	var previousPlayState by remember { mutableStateOf(playState) }
	var showLiveTvGuide by remember { mutableStateOf(false) }
	val coroutineScope = rememberCoroutineScope()
	val keepOverlayVisible = playState == PlayState.PAUSED && !pausedOverlayDismissed && !seekPauseOverlaySuppressed
	val visibilityState = rememberPlayerOverlayVisibility(keepVisible = keepOverlayVisible)
	val windowInfo = LocalWindowInfo.current
	val entry by rememberQueueEntry(playbackManager)
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value
	val currentLiveTvItem = item?.takeIf { baseItem -> baseItem.isLiveTv() }
	val liveTvProgramTimeline = rememberLiveTvProgramTimeline(item)
	val liveTvProgramPosition = rememberLiveTvProgramPosition(liveTvProgramTimeline)
	val liveTvGuideKeyEventHandler = remember { KeyEventHandlerHolder() }

	fun hideOverlay() {
		pausedOverlayDismissed = true
		visibilityState.hide()
	}

	fun requestStopConfirmation() {
		val now = SystemClock.elapsedRealtime()
		if (now <= backToStopDeadline) {
			backToStopDeadline = 0L
			onClosePlayer()
			return
		}

		backToStopDeadline = now + BackToStopTimeout.inWholeMilliseconds
		hideOverlay()
		mediaToastRegistry.emit(
			icon = R.drawable.ic_stop,
			text = R.string.player_back_to_stop,
			duration = BackToStopTimeout,
		)
	}

	fun dismissLiveTvGuide() {
		showLiveTvGuide = false
		visibilityState.show()
	}

	BackHandler(enabled = windowInfo.isWindowFocused) {
		if (showLiveTvGuide) {
			dismissLiveTvGuide()
		} else {
			requestStopConfirmation()
		}
	}

	LaunchedEffect(playState) {
		if (previousPlayState == PlayState.PAUSED && playState == PlayState.PLAYING) {
			pausedOverlayDismissed = true
			visibilityState.hide()
			seekPauseOverlaySuppressed = false
		} else {
			pausedOverlayDismissed = false
		}
		previousPlayState = playState
	}

	fun togglePlayback() {
		when (playState) {
			PlayState.STOPPED,
			PlayState.ERROR -> playbackManager.state.play()

			PlayState.PLAYING -> playbackManager.state.pause()
			PlayState.PAUSED -> playbackManager.state.unpause()
		}
	}

	fun clearCenterLongPress() {
		centerLongPressJob?.cancel()
		centerLongPressJob = null
		centerLongPressTriggered = false
	}

	fun showSeekOverlay() {
		if (visibilityState.visible) return

		seekOverlayVisible = true
		seekOverlayJob?.cancel()
		seekOverlayJob = coroutineScope.launch {
			delay(SeekOverlayDuration)
			seekOverlayVisible = false
		}
	}

	fun clampSeekPosition(position: Duration, duration: Duration): Duration {
		if (duration <= Duration.ZERO) return position.coerceAtLeast(Duration.ZERO)
		return position.coerceIn(Duration.ZERO, duration)
	}

	fun commitPendingSeek() {
		val target = pendingSeekPosition ?: return

		pendingSeekCommitJob?.cancel()
		pendingSeekCommitJob = null
		pendingSeekPosition = null

		seekPauseOverlaySuppressed = true
		playbackManager.state.setScrubbing(true)
		playbackManager.state.seek(target)

		seekScrubJob?.cancel()
		seekScrubJob = coroutineScope.launch {
			delay(DpadSeekScrubDuration)
			playbackManager.state.setScrubbing(false)
		}
	}

	fun dpadSeek(forward: Boolean) {
		val positionInfo = playbackManager.state.positionInfo
		val amount = if (forward) {
			playbackManager.options.defaultFastForwardAmount()
		} else {
			-playbackManager.options.defaultRewindAmount()
		}
		val basePosition = pendingSeekPosition ?: positionInfo.active

		pendingSeekPosition = clampSeekPosition(basePosition + amount, positionInfo.duration)
		pendingSeekCommitJob?.cancel()
		pendingSeekCommitJob = coroutineScope.launch {
			delay(DpadSeekCommitDelay)
			commitPendingSeek()
		}

		mediaToastRegistry.emit(
			icon = if (forward) R.drawable.ic_fast_forward else R.drawable.ic_rewind,
			duration = DpadSeekToastDuration,
		)
	}

	SideEffect {
		onRemoteKeyEventHandlerChanged handler@{ keyCode, event ->
			val keyEvent = event ?: return@handler false

			if (showLiveTvGuide) {
				if (keyEvent.isBackKey()) {
					if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
						dismissLiveTvGuide()
					}
					return@handler true
				}

				liveTvGuideKeyEventHandler.handler?.invoke(keyEvent)
				return@handler true
			}

			if (keyEvent.action == KeyEvent.ACTION_UP && skipPromptKeyCode == keyCode) {
				skipPromptKeyCode = null
				return@handler true
			}

			if (keyEvent.isCenterKey()) {
				val promptTarget = skipPromptTarget
				if (promptTarget != null) {
					if (keyEvent.action == KeyEvent.ACTION_DOWN) {
						clearCenterLongPress()
						centerShortcutKeyCode = null
						skipPromptKeyCode = keyCode
						playbackManager.state.seek(promptTarget)
						skipPromptTarget = null
					}
					return@handler true
				}
			}

			if (keyEvent.action == KeyEvent.ACTION_UP && centerShortcutKeyCode == keyCode) {
				val shouldTogglePlayback = !centerLongPressTriggered
				centerShortcutKeyCode = null
				clearCenterLongPress()
				if (shouldTogglePlayback) togglePlayback()
				return@handler true
			}

			if (keyEvent.action == KeyEvent.ACTION_UP && overlayWakeKeyCode == keyCode) {
				if (keyEvent.isSeekKey()) commitPendingSeek()
				overlayWakeKeyCode = null
				return@handler true
			}

			if (keyEvent.isBackKey()) {
				when (keyEvent.action) {
					KeyEvent.ACTION_DOWN -> {
						if (keyEvent.repeatCount == 0) {
							requestStopConfirmation()
							backToStopKeyCode = keyCode
						}
						return@handler true
					}

					KeyEvent.ACTION_UP -> {
						if (backToStopKeyCode == keyCode) {
							backToStopKeyCode = null
							return@handler true
						}
					}
				}
				return@handler true
			}

			if (!showLiveTvGuide && keyEvent.isDpadDownKey() && currentLiveTvItem != null) {
				if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
					clearCenterLongPress()
					showLiveTvGuide = true
					showPlaybackInfo = false
					seekOverlayVisible = false
					visibilityState.hide()
				}
				return@handler true
			}

			if (keyEvent.isCenterKey() && !visibilityState.visible) {
				when (keyEvent.action) {
					KeyEvent.ACTION_DOWN -> {
						if (keyEvent.repeatCount == 0) {
							centerShortcutKeyCode = keyCode
							centerLongPressTriggered = false
							centerLongPressJob?.cancel()
							centerLongPressJob = coroutineScope.launch {
								delay(CenterLongPressDuration)
								centerLongPressTriggered = true
								showPlaybackInfo = !showPlaybackInfo
							}
						}
						return@handler true
					}

					KeyEvent.ACTION_UP -> {
						clearCenterLongPress()
						return@handler true
					}
				}
			}

			if (keyEvent.isSeekKey() && !visibilityState.visible) {
				if (keyEvent.action == KeyEvent.ACTION_DOWN) {
					clearCenterLongPress()
					overlayWakeKeyCode = keyCode
					showSeekOverlay()
					dpadSeek(forward = keyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
				}
				return@handler true
			}

			if (keyEvent.action == KeyEvent.ACTION_DOWN && !keyEvent.isSystem && !visibilityState.visible) {
				clearCenterLongPress()
				overlayWakeKeyCode = keyCode
				visibilityState.show()
				return@handler true
			}

			false
		}
	}

	DisposableEffect(onRemoteKeyEventHandlerChanged) {
		onDispose {
			centerLongPressJob?.cancel()
			seekOverlayJob?.cancel()
			pendingSeekCommitJob?.cancel()
			seekScrubJob?.cancel()
			playbackManager.state.setScrubbing(false)
			onRemoteKeyEventHandlerChanged(null)
		}
	}

	Box(modifier = modifier) {
		PlayerOverlayLayout(
			visibilityState = visibilityState,
			header = {
				Column {
					VideoPlayerHeader(
						item = item,
						liveTvProgramName = liveTvProgramTimeline?.programName,
					)
					PlaybackDebugInfo(
						playbackManager = playbackManager,
						modifier = Modifier.fillMaxWidth(0.82f),
					)
				}
			},
			controls = {
				VideoPlayerControls(
					playbackManager = playbackManager,
					item = item,
					zoomMode = zoomMode,
					onZoomModeSelected = onZoomModeSelected,
					onPlaybackInfoClick = { showPlaybackInfo = !showPlaybackInfo },
					onStopClick = onClosePlayer,
					liveTvProgramTimeline = liveTvProgramTimeline,
					liveTvProgramPosition = liveTvProgramPosition,
				)
			},
			onCenterClick = {
				val promptTarget = skipPromptTarget
				if (promptTarget != null) {
					playbackManager.state.seek(promptTarget)
					skipPromptTarget = null
					return@PlayerOverlayLayout true
				}

				togglePlayback()
				true
			},
			onCenterLongClick = {
				showPlaybackInfo = !showPlaybackInfo
				true
			},
		)

		AnimatedVisibility(
			visible = seekOverlayVisible && !visibilityState.visible,
			modifier = Modifier.align(Alignment.BottomCenter),
			enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
			exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
		) {
			Box(
				contentAlignment = Alignment.BottomCenter,
				modifier = Modifier
					.fillMaxWidth()
					.fillMaxHeight(1f / 4)
					.background(
						brush = Brush.verticalGradient(
							colors = listOf(
								Color.Transparent,
								Color.Black.copy(alpha = 0.8f),
							)
						)
					)
					.overscan()
					.padding(bottom = 40.dp)
			) {
				VideoPlayerSeekControls(
					playbackManager = playbackManager,
					position = pendingSeekPosition,
					liveTvProgramTimeline = liveTvProgramTimeline,
					liveTvProgramPosition = liveTvProgramPosition,
				)
			}
		}

		// Playback info overlay - positioned below header area, always visible when enabled
		if (showPlaybackInfo) {
			PlaybackInfoOverlay(
				playbackManager = playbackManager,
				modifier = Modifier
					.align(Alignment.TopEnd)
					.padding(top = 68.dp, end = 48.dp)
			)
		}

		VideoPlayerMediaSegmentOverlay(
			playbackManager = playbackManager,
			item = item,
			onPromptTargetChanged = { skipPromptTarget = it },
			modifier = Modifier
				.align(Alignment.BottomEnd)
				.padding(
					start = 48.dp,
					top = 48.dp,
					end = 48.dp,
					bottom = when {
						visibilityState.visible -> 220.dp
						seekOverlayVisible -> 140.dp
						else -> 48.dp
					},
				)
		)

		if (showLiveTvGuide && currentLiveTvItem != null) {
			LiveTvGuideOverlay(
				playbackManager = playbackManager,
				currentItem = currentLiveTvItem,
				onDismiss = ::dismissLiveTvGuide,
				onRemoteKeyEventHandlerChanged = { handler ->
					liveTvGuideKeyEventHandler.handler = handler
				},
			)
		}

		MediaToasts(mediaToastRegistry)
	}
}

private fun KeyEvent.isCenterKey() = when (keyCode) {
	KeyEvent.KEYCODE_DPAD_CENTER,
	KeyEvent.KEYCODE_ENTER,
	KeyEvent.KEYCODE_NUMPAD_ENTER,
	KeyEvent.KEYCODE_BUTTON_A -> true

	else -> false
}

private fun KeyEvent.isBackKey() = when (keyCode) {
	KeyEvent.KEYCODE_BACK,
	KeyEvent.KEYCODE_BUTTON_B,
	KeyEvent.KEYCODE_ESCAPE -> true

	else -> false
}

private fun KeyEvent.isSeekKey() = when (keyCode) {
	KeyEvent.KEYCODE_DPAD_LEFT,
	KeyEvent.KEYCODE_DPAD_RIGHT -> true

	else -> false
}

private fun KeyEvent.isDpadDownKey() = keyCode == KeyEvent.KEYCODE_DPAD_DOWN

private class KeyEventHandlerHolder {
	var handler: ((KeyEvent) -> Boolean)? = null
}
