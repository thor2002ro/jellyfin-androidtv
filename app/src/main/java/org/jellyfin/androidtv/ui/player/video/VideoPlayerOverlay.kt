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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.composable.modifier.overscan
import org.jellyfin.androidtv.ui.player.base.PlayerOverlayLayout
import org.jellyfin.androidtv.ui.player.base.rememberPlayerOverlayVisibility
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.base.toast.MediaToasts
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.util.sdk.isLiveTv
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.isActivePlayback
import org.jellyfin.playback.core.queue.isDirectPlayLiveTv
import org.jellyfin.playback.core.queue.isLiveTv
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.koin.compose.koinInject
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val CenterLongPressDuration = 2.seconds
private val SeekOverlayDuration = 2.seconds
private val SeekPreviewDuration = 2500.milliseconds
private val DpadSeekScrubDuration = 750.milliseconds
private val DpadSeekCommitDelay = 1500.milliseconds
private val DpadSeekToastDuration = 1500.milliseconds
private val BackToStopTimeout = 2.seconds
private val BackDuplicateGuardDuration = 250.milliseconds

@Composable
fun VideoPlayerOverlay(
	modifier: Modifier = Modifier,
	playbackManager: PlaybackManager = koinInject(),
	mediaToastRegistry: MediaToastRegistry,
	zoomMode: ZoomMode,
	onZoomModeSelected: (ZoomMode) -> Unit,
	onRemoteKeyEventHandlerChanged: (((keyCode: Int, event: KeyEvent?) -> Boolean)?) -> Unit = {},
	onClosePlayer: () -> Unit = {},
	openLiveTvGuideOnStart: Boolean = false,
) {
	val playState by playbackManager.state.playState.collectAsState()
	val videoQueueManager = koinInject<VideoQueueManager>()
	val userPreferences = koinInject<UserPreferences>()
	var pausedOverlayDismissed by remember { mutableStateOf(false) }
	var showPlaybackInfo by remember { mutableStateOf(false) }
	var openLiveTvGuideOnStartConsumed by remember { mutableStateOf(false) }
	var skipPromptTarget by remember { mutableStateOf<Duration?>(null) }
	var endingSkipPromptVisible by remember { mutableStateOf(false) }
	var overlayWakeKeyCode by remember { mutableStateOf<Int?>(null) }
	var nextUpPromptKeyCode by remember { mutableStateOf<Int?>(null) }
	var skipPromptKeyCode by remember { mutableStateOf<Int?>(null) }
	var centerShortcutKeyCode by remember { mutableStateOf<Int?>(null) }
	var seekPauseOverlaySuppressed by remember { mutableStateOf(false) }
	var seekOverlayVisible by remember { mutableStateOf(false) }
	var seekOverlayJob by remember { mutableStateOf<Job?>(null) }
	var pendingSeekPosition by remember { mutableStateOf<Duration?>(null) }
	var seekPreviewPosition by remember { mutableStateOf<Duration?>(null) }
	var seekPreviewJob by remember { mutableStateOf<Job?>(null) }
	var pendingSeekCommitJob by remember { mutableStateOf<Job?>(null) }
	var seekScrubJob by remember { mutableStateOf<Job?>(null) }
	var backToStopDeadline by remember { mutableStateOf(0L) }
	var backToStopKeyCode by remember { mutableStateOf<Int?>(null) }
	var lastBackRequestAt by remember { mutableStateOf(0L) }
	var centerLongPressTriggered by remember { mutableStateOf(false) }
	var centerLongPressJob by remember { mutableStateOf<Job?>(null) }
	var previousPlayState by remember { mutableStateOf(playState) }
	var showLiveTvGuide by remember { mutableStateOf(false) }
	var nextUpStartInProgress by remember { mutableStateOf(false) }
	val coroutineScope = rememberCoroutineScope()
	val keepOverlayVisible = playState == PlayState.PAUSED && !pausedOverlayDismissed && !seekPauseOverlaySuppressed
	val visibilityState = rememberPlayerOverlayVisibility(keepVisible = keepOverlayVisible)
	val windowInfo = LocalWindowInfo.current
	val entry by rememberQueueEntry(playbackManager)
	val entryIndex by playbackManager.queue.entryIndex.collectAsState()
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value
	val videoQueue = videoQueueManager.getCurrentVideoQueue()
	val nextItem = videoQueue
		.getOrNull(entryIndex + 1)
		.takeIf { entryIndex >= 0 && entryIndex < videoQueue.lastIndex }
	val currentLiveTvItem = item?.takeIf { baseItem -> baseItem.isLiveTv() }
	var liveTvGuideItem by remember { mutableStateOf(currentLiveTvItem) }
	val playPauseEnabled = entry?.isLiveTv != true
	val seekEnabled = entry?.isDirectPlayLiveTv != true
	val liveTvProgramTimeline = rememberLiveTvProgramTimeline(item)
	val liveTvProgramPosition = rememberLiveTvProgramPosition(liveTvProgramTimeline)
	val liveTvGuideKeyEventHandler = remember { KeyEventHandlerHolder() }
	val nextUpBehavior = userPreferences[UserPreferences.nextUpBehavior]
	val trickPlayEnabled = userPreferences[UserPreferences.trickPlayEnabled]
	val nextUpPositionInfo by rememberPlayerPositionInfo(
		playbackManager = playbackManager,
		precision = NextUpProgressCheckInterval,
		resetKey = entryIndex,
		resetToEmpty = true,
		updateImmediately = false,
	)
	val nextUpPromptVisible = item.isNextUpPromptVisible(
		nextItem = nextItem,
		nextUpBehavior = nextUpBehavior,
		positionInfo = nextUpPositionInfo,
		endingSkipPromptVisible = endingSkipPromptVisible,
	)

	fun hideOverlay() {
		pausedOverlayDismissed = true
		visibilityState.hide()
	}

	fun requestStopConfirmation() {
		val now = SystemClock.elapsedRealtime()
		if (now - lastBackRequestAt <= BackDuplicateGuardDuration.inWholeMilliseconds) return
		lastBackRequestAt = now

		if (now <= backToStopDeadline) {
			backToStopDeadline = 0L
			onClosePlayer()
			return
		}

		backToStopDeadline = now + BackToStopTimeout.inWholeMilliseconds
		if (playState != PlayState.PAUSED) hideOverlay()
		mediaToastRegistry.emit(
			icon = R.drawable.ic_stop,
			text = R.string.player_back_to_stop,
			duration = BackToStopTimeout,
		)
	}

	fun dismissLiveTvGuide() {
		showLiveTvGuide = false
		liveTvGuideItem = null
		visibilityState.show()
	}

	fun closeLiveTvGuide() {
		dismissLiveTvGuide()
	}

	fun shouldHandleNextUpPrompt() = nextUpPromptVisible || nextUpStartInProgress

	fun startNextItemNow() {
		if (nextUpStartInProgress) return
		val nextIndex = playbackManager.queue.entryIndex.value + 1
		if (nextIndex <= 0 || nextIndex > videoQueueManager.getCurrentVideoQueue().lastIndex) return

		nextUpStartInProgress = true
		centerShortcutKeyCode = null
		coroutineScope.launch {
			val nextEntry = playbackManager.queue.peekNext(usePlaybackOrder = false, useRepeatMode = false)
			if (nextEntry == null) {
				nextUpStartInProgress = false
				return@launch
			}

			// The next entry may have been preloaded before the latest subtitle selection was known.
			nextEntry.mediaStream = null

			if (playbackManager.queue.next(usePlaybackOrder = false, useRepeatMode = false) == null) {
				nextUpStartInProgress = false
			} else {
				videoQueueManager.setCurrentMediaPosition(nextIndex)
			}
		}
	}

	BackHandler(enabled = windowInfo.isWindowFocused) {
		if (showLiveTvGuide) {
			closeLiveTvGuide()
		} else {
			requestStopConfirmation()
		}
	}

	LaunchedEffect(playState) {
		if (previousPlayState == PlayState.PAUSED && playState.isActivePlayback) {
			pausedOverlayDismissed = true
			visibilityState.hide()
			seekPauseOverlaySuppressed = false
		} else {
			pausedOverlayDismissed = false
		}
		previousPlayState = playState
	}

	LaunchedEffect(entryIndex) {
		nextUpStartInProgress = false
	}

	LaunchedEffect(currentLiveTvItem?.id) {
		if (currentLiveTvItem != null) liveTvGuideItem = currentLiveTvItem
	}

	LaunchedEffect(openLiveTvGuideOnStart, currentLiveTvItem?.id) {
		val liveTvItem = currentLiveTvItem ?: return@LaunchedEffect
		if (!openLiveTvGuideOnStart || openLiveTvGuideOnStartConsumed) return@LaunchedEffect

		openLiveTvGuideOnStartConsumed = true
		liveTvGuideItem = liveTvItem
		showLiveTvGuide = true
		showPlaybackInfo = false
		seekOverlayVisible = false
		visibilityState.hide()
	}

	LaunchedEffect(item?.id, nextItem?.id, nextUpPromptVisible) {
		val next = nextItem ?: return@LaunchedEffect
		if (!nextUpPromptVisible) return@LaunchedEffect

		Timber.i(
			"Showing in-player Next Up for item ${next.id} at ${nextUpPositionInfo.active} of ${nextUpPositionInfo.duration}"
		)
	}

	fun togglePlayback() {
		if (!playPauseEnabled) return

		when (playState) {
			PlayState.STOPPED,
			PlayState.ERROR -> playbackManager.state.play()

			PlayState.PLAYING -> playbackManager.state.pause()
			PlayState.BUFFERING -> playbackManager.state.pause()
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

	fun showSeekPreview(position: Duration) {
		seekPreviewPosition = position
		seekPreviewJob?.cancel()
		seekPreviewJob = coroutineScope.launch {
			delay(SeekPreviewDuration)
			seekPreviewPosition = null
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

	fun previewSeek(position: Duration) {
		val positionInfo = playbackManager.state.positionInfo
		val target = clampSeekPosition(position, positionInfo.duration)
		pendingSeekPosition = target
		showSeekPreview(target)
		pendingSeekCommitJob?.cancel()
		pendingSeekCommitJob = coroutineScope.launch {
			delay(DpadSeekCommitDelay)
			commitPendingSeek()
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

		previewSeek(basePosition + amount)

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
						closeLiveTvGuide()
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

			if (keyEvent.action == KeyEvent.ACTION_UP && nextUpPromptKeyCode == keyCode) {
				nextUpPromptKeyCode = null
				return@handler true
			}

			if (keyEvent.isCenterKey()) {
				val promptTarget = skipPromptTarget
				if (promptTarget != null && !(endingSkipPromptVisible && shouldHandleNextUpPrompt())) {
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

			if (shouldHandleNextUpPrompt() && keyEvent.isCenterKey()) {
				when (keyEvent.action) {
					KeyEvent.ACTION_DOWN -> {
						if (keyEvent.repeatCount == 0) {
							clearCenterLongPress()
							centerShortcutKeyCode = null
							nextUpPromptKeyCode = keyCode
							startNextItemNow()
						}
						return@handler true
					}

					KeyEvent.ACTION_UP -> {
						nextUpPromptKeyCode = null
						return@handler true
					}
				}
			}

			if (keyEvent.action == KeyEvent.ACTION_UP && centerShortcutKeyCode == keyCode) {
				val shouldTogglePlayback = !centerLongPressTriggered
				centerShortcutKeyCode = null
				clearCenterLongPress()
				if (shouldTogglePlayback) {
					togglePlayback()
					visibilityState.show()
				}
				return@handler true
			}

			if (keyEvent.action == KeyEvent.ACTION_UP && overlayWakeKeyCode == keyCode) {
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

			if (!showLiveTvGuide && keyEvent.isDpadUpKey() && currentLiveTvItem != null && visibilityState.visible) {
				if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
					clearCenterLongPress()
					liveTvGuideItem = currentLiveTvItem
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

			if (seekEnabled && keyEvent.isSeekKey() && !visibilityState.visible) {
				if (keyEvent.action == KeyEvent.ACTION_DOWN) {
					clearCenterLongPress()
					overlayWakeKeyCode = keyCode
					showSeekOverlay()
					dpadSeek(forward = keyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
				}
				return@handler true
			}

			if (seekEnabled && keyEvent.isMediaSeekKey()) {
				if (keyEvent.action == KeyEvent.ACTION_DOWN) {
					clearCenterLongPress()
					overlayWakeKeyCode = keyCode
					showSeekOverlay()
					dpadSeek(forward = keyEvent.isForwardSeekKey())
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
			seekPreviewJob?.cancel()
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
						liveTvNextProgram = liveTvProgramTimeline?.nextProgram,
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
					mediaSourceId = entry?.mediaSourceId,
					trickPlayEnabled = trickPlayEnabled,
					seekPreviewPosition = seekPreviewPosition,
					onSeekPreview = { previewSeek(it) },
					zoomMode = zoomMode,
					onZoomModeSelected = onZoomModeSelected,
					onPlaybackInfoClick = { showPlaybackInfo = !showPlaybackInfo },
					onStopClick = onClosePlayer,
					liveTvProgramTimeline = liveTvProgramTimeline,
					liveTvProgramPosition = liveTvProgramPosition,
				)
			},
			onCenterClick = {
				if (endingSkipPromptVisible && shouldHandleNextUpPrompt()) {
					startNextItemNow()
					return@PlayerOverlayLayout true
				}

				val promptTarget = skipPromptTarget
				if (promptTarget != null) {
					playbackManager.state.seek(promptTarget)
					skipPromptTarget = null
					return@PlayerOverlayLayout true
				}

				if (shouldHandleNextUpPrompt()) {
					startNextItemNow()
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
			visible = seekEnabled && seekOverlayVisible && !visibilityState.visible,
			modifier = Modifier.align(Alignment.BottomCenter),
			enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
			exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
		) {
			BoxWithConstraints(
				contentAlignment = Alignment.BottomCenter,
				modifier = Modifier
					.fillMaxWidth()
					.fillMaxHeight(1f / 3)
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
				val previewPosition = pendingSeekPosition ?: seekPreviewPosition
				val duration = playbackManager.state.positionInfo.duration
				val progress = if (previewPosition != null && duration > Duration.ZERO) {
					previewPosition.inWholeMilliseconds.toFloat()
						.div(duration.inWholeMilliseconds.toFloat())
						.coerceIn(0f, 1f)
				} else {
					0f
				}
				val maxThumbnailX = (maxWidth - TrickplayThumbnailWidth).coerceAtLeast(0.dp)
				val thumbnailX = (maxWidth * progress - TrickplayThumbnailWidth / 2)
					.coerceIn(0.dp, maxThumbnailX)

				VideoPlayerSeekControls(
					playbackManager = playbackManager,
					position = previewPosition,
					liveTvProgramTimeline = liveTvProgramTimeline,
					liveTvProgramPosition = liveTvProgramPosition,
					enabled = seekEnabled,
				)
				VideoPlayerTrickplayThumbnail(
					item = item,
					mediaSourceId = entry?.mediaSourceId,
					position = previewPosition.takeIf { liveTvProgramTimeline == null },
					enabled = trickPlayEnabled,
					modifier = Modifier
						.align(Alignment.BottomStart)
						.offset(x = thumbnailX, y = (-72).dp),
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

		val promptBottomPadding = when {
			visibilityState.visible -> 220.dp
			seekOverlayVisible -> 140.dp
			else -> 48.dp
		}

		VideoPlayerMediaSegmentOverlay(
			playbackManager = playbackManager,
			item = item,
			onPromptTargetChanged = { skipPromptTarget = it },
			onEndingSkipPromptChanged = { endingSkipPromptVisible = it },
			modifier = Modifier
				.align(Alignment.BottomEnd)
				.padding(
					start = 48.dp,
					top = 48.dp,
					end = 48.dp,
					bottom = promptBottomPadding,
				)
		)

		VideoPlayerNextUpOverlay(
			nextItem = nextItem,
			nextUpBehavior = nextUpBehavior,
			visible = nextUpPromptVisible,
			controlsVisible = visibilityState.visible,
			seekOverlayVisible = seekOverlayVisible,
			modifier = Modifier
				.align(Alignment.BottomEnd)
				.padding(
					start = 48.dp,
					top = 48.dp,
					end = 48.dp,
					bottom = promptBottomPadding + if (skipPromptTarget != null) 56.dp else 0.dp,
				)
		)

		val guideItem = liveTvGuideItem
		if (showLiveTvGuide && guideItem != null) {
			LiveTvGuideOverlay(
				playbackManager = playbackManager,
				currentItem = guideItem,
				onDismiss = ::closeLiveTvGuide,
				showPreview = !openLiveTvGuideOnStart,
				onCurrentChannelSelected = { dismissLiveTvGuide() },
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

private fun KeyEvent.isMediaSeekKey() = when (keyCode) {
	KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
	KeyEvent.KEYCODE_MEDIA_REWIND,
	KeyEvent.KEYCODE_BUTTON_R1,
	KeyEvent.KEYCODE_BUTTON_R2,
	KeyEvent.KEYCODE_BUTTON_L1,
	KeyEvent.KEYCODE_BUTTON_L2 -> true

	else -> false
}

private fun KeyEvent.isForwardSeekKey() = when (keyCode) {
	KeyEvent.KEYCODE_DPAD_RIGHT,
	KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
	KeyEvent.KEYCODE_BUTTON_R1,
	KeyEvent.KEYCODE_BUTTON_R2 -> true

	else -> false
}

private fun KeyEvent.isDpadUpKey() = keyCode == KeyEvent.KEYCODE_DPAD_UP

private class KeyEventHandlerHolder {
	var handler: ((KeyEvent) -> Boolean)? = null
}
