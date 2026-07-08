package org.jellyfin.androidtv.ui.player.video

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Seekbar
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.button.IconButtonDefaults
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.player.base.PlayerSeekbar
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.androidtv.util.sdk.buildChapterItems
import org.jellyfin.androidtv.util.sdk.isLiveTv
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.queue.isDirectPlayLiveTv
import org.jellyfin.playback.core.queue.isLiveTv
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.compose.koinInject
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

private val UpHintVerticalOffset = (-34).dp
private val UpHintIconSize = 16.dp
private const val DefaultPlaybackSpeed = 1.0

@Composable
fun VideoPlayerControls(
	playbackManager: PlaybackManager = koinInject(),
	item: BaseItemDto? = null,
	mediaSourceId: String? = null,
	trickPlayEnabled: Boolean = false,
	seekPreviewPosition: Duration? = null,
	onSeekPreview: (Duration) -> Unit = {},
	onRelativeSeekPreview: (Boolean) -> Unit = {},
	zoomMode: ZoomMode,
	onZoomModeSelected: (ZoomMode) -> Unit,
	onPlaybackInfoClick: () -> Unit = {},
	onStopClick: () -> Unit = {},
	liveTvProgramTimeline: LiveTvProgramTimeline? = null,
	liveTvProgramPosition: Duration = Duration.ZERO,
) {
	val playState by playbackManager.state.playState.collectAsState()
	val currentQueueEntry by playbackManager.queue.entry.collectAsState()
	val entryIndex by playbackManager.queue.entryIndex.collectAsState()
	val chapters = remember(item) { item?.buildChapterItems().orEmpty() }
	val chapterMarkers = remember(chapters) { chapters.map { chapter -> chapter.startPositionTicks.ticks } }
	var chaptersExpanded by remember(item?.id) { mutableStateOf(false) }
	var restoreFocusToControls by remember(item?.id) { mutableStateOf(false) }
	var changingLiveTvChannel by remember(item?.id) { mutableStateOf(false) }
	val topControlsFocusRequester = remember { FocusRequester() }
	val isLiveTv = item?.isLiveTv() == true
	val playPauseEnabled = currentQueueEntry?.isLiveTv != true
	val seekEnabled = currentQueueEntry?.isDirectPlayLiveTv != true
	val coroutineScope = rememberCoroutineScope()
	val liveTvChannelNavigator = rememberLiveTvChannelNavigator()
	val showPreviousEntry = isLiveTv || entryIndex > 0
	val showNextEntry = isLiveTv || (entryIndex >= 0 && entryIndex < playbackManager.queue.estimatedSize - 1)

	LaunchedEffect(chapters) {
		if (chapters.isEmpty()) {
			chaptersExpanded = false
			restoreFocusToControls = false
		}
	}

	LaunchedEffect(chaptersExpanded, restoreFocusToControls) {
		if (!chaptersExpanded && restoreFocusToControls) {
			topControlsFocusRequester.requestFocus()
			restoreFocusToControls = false
		}
	}

	fun dismissChaptersAndRestoreControls() {
		if (chaptersExpanded) {
			chaptersExpanded = false
			restoreFocusToControls = true
		}
	}

	fun switchLiveTvChannel(direction: LiveTvChannelDirection) {
		val currentItem = item ?: return
		if (changingLiveTvChannel) return

		coroutineScope.launch {
			changingLiveTvChannel = true
			try {
				liveTvChannelNavigator.switchChannel(
					playbackManager = playbackManager,
					currentItem = currentItem,
					direction = direction,
				)
			} finally {
				changingLiveTvChannel = false
			}
		}
	}

	Column(
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
		modifier = Modifier.fillMaxWidth(),
	) {
		BoxWithConstraints(
			modifier = Modifier.fillMaxWidth(),
		) {
			if (isLiveTv || (chapters.isNotEmpty() && !chaptersExpanded)) {
				UpHint(
					text = stringResource(if (isLiveTv) R.string.lbl_live_tv_guide else R.string.chapters),
					modifier = Modifier
						.align(Alignment.TopCenter)
						.offset(y = UpHintVerticalOffset)
				)
			}

			Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				modifier = Modifier
					.fillMaxWidth()
					.focusRestorer()
					.focusGroup()
					.onPreviewKeyEvent { event ->
						val nativeEvent = event.nativeKeyEvent
						if (
							nativeEvent.action == KeyEvent.ACTION_DOWN &&
							nativeEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
							nativeEvent.repeatCount == 0 &&
							chapters.isNotEmpty()
						) {
							chaptersExpanded = true
							true
						} else {
							false
						}
					}
			) {
				PlayPauseButton(
					playbackManager = playbackManager,
					playState = playState,
					focusRequester = topControlsFocusRequester,
					enabled = playPauseEnabled,
				)
				StopButton(onClick = onStopClick)
				RewindButton(
					playbackManager = playbackManager,
					enabled = seekEnabled,
					onPreview = { onRelativeSeekPreview(false) },
				)
				FastForwardButton(
					playbackManager = playbackManager,
					enabled = seekEnabled,
					onPreview = { onRelativeSeekPreview(true) },
				)

				Spacer(Modifier.weight(1f))

				PlaybackInfoButton(onClick = onPlaybackInfoClick)
				ZoomModeButton(
					zoomMode = zoomMode,
					onZoomModeSelected = onZoomModeSelected,
				)
				PlaybackSpeedButton(playbackManager, item)
				QualityProfileButton(playbackManager, item)
				AudioTrackButton(playbackManager)
				SubtitleTrackButton(playbackManager)
			}

			ChapterListPopover(
				expanded = chaptersExpanded,
				onDismissRequest = ::dismissChaptersAndRestoreControls,
				chapters = chapters,
				item = item,
				mediaSourceId = mediaSourceId,
				trickPlayEnabled = trickPlayEnabled,
				width = maxWidth,
				playbackManager = playbackManager,
			)
		}

		VideoPlayerTrickplayThumbnail(
			item = item,
			mediaSourceId = mediaSourceId,
			position = seekPreviewPosition.takeIf { liveTvProgramTimeline == null },
			enabled = trickPlayEnabled,
		)

		TimelineSeekbar(
			playbackManager = playbackManager,
			position = seekPreviewPosition,
			markers = chapterMarkers,
			liveTvProgramTimeline = liveTvProgramTimeline,
			liveTvProgramPosition = liveTvProgramPosition,
			onSeek = onSeekPreview,
			enabled = seekEnabled,
			modifier = Modifier
				.fillMaxWidth()
				.height(4.dp),
		)

		Row(
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier
				.fillMaxWidth()
				.focusRestorer()
				.focusGroup()
		) {
			if (showPreviousEntry) {
				PreviousEntryButton(
					enabled = !changingLiveTvChannel,
					onClick = {
						if (isLiveTv) {
							switchLiveTvChannel(LiveTvChannelDirection.PREVIOUS)
						} else {
							coroutineScope.launch {
								playbackManager.queue.previous()
							}
						}
					},
				)
			}
			if (showNextEntry) {
				NextEntryButton(
					enabled = !changingLiveTvChannel,
					onClick = {
						if (isLiveTv) {
							switchLiveTvChannel(LiveTvChannelDirection.NEXT)
						} else {
							coroutineScope.launch {
								playbackManager.queue.next()
							}
						}
					},
				)
			}

			Spacer(Modifier.weight(1f))
			PositionText(
				playbackManager = playbackManager,
				liveTvProgramTimeline = liveTvProgramTimeline,
				liveTvProgramPosition = liveTvProgramPosition,
			)
		}
	}
}

@Composable
private fun UpHint(
	text: String,
	modifier: Modifier = Modifier,
) {
	val color = Color.White.copy(alpha = 0.72f)

	Column(
		horizontalAlignment = Alignment.CenterHorizontally,
		modifier = modifier,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_arrow_up),
			contentDescription = null,
			tint = color,
			modifier = Modifier.size(UpHintIconSize),
		)
		Text(
			text = text,
			style = JellyfinTheme.typography.listCaption.copy(
				color = color,
			),
			maxLines = 1,
		)
	}
}

@Composable
fun VideoPlayerSeekControls(
	playbackManager: PlaybackManager = koinInject(),
	position: Duration? = null,
	liveTvProgramTimeline: LiveTvProgramTimeline? = null,
	liveTvProgramPosition: Duration = Duration.ZERO,
	enabled: Boolean = true,
) {
	Column(
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
		modifier = Modifier.fillMaxWidth(),
	) {
		TimelineSeekbar(
			playbackManager = playbackManager,
			position = position,
			liveTvProgramTimeline = liveTvProgramTimeline,
			liveTvProgramPosition = liveTvProgramPosition,
			enabled = enabled,
			modifier = Modifier
				.fillMaxWidth()
				.height(4.dp),
		)

		Row(
			modifier = Modifier.fillMaxWidth(),
		) {
			Spacer(Modifier.weight(1f))
			PositionText(
				playbackManager = playbackManager,
				position = position.takeIf { liveTvProgramTimeline == null },
				liveTvProgramTimeline = liveTvProgramTimeline,
				liveTvProgramPosition = liveTvProgramPosition,
			)
		}
	}
}

@Composable
private fun PlayPauseButton(
	playbackManager: PlaybackManager,
	playState: PlayState,
	focusRequester: FocusRequester,
	enabled: Boolean = true,
) {
	val tooltip = stringResource(
		when (playState) {
			PlayState.PLAYING,
			PlayState.BUFFERING -> R.string.lbl_pause

			PlayState.STOPPED,
			PlayState.PAUSED,
			PlayState.ERROR -> R.string.lbl_play
		}
	)
	val lifecycleOwner = LocalLifecycleOwner.current
	IconButton(
		onClick = {
			when (playState) {
				PlayState.STOPPED,
				PlayState.ERROR -> playbackManager.state.play()

				PlayState.PLAYING -> playbackManager.state.pause()
				PlayState.BUFFERING -> playbackManager.state.pause()
				PlayState.PAUSED -> playbackManager.state.unpause()
			}
		},
		modifier = Modifier
			.then(
				if (enabled) {
					Modifier
						.focusRequester(focusRequester)
						.onVisibilityChanged {
							if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
								focusRequester.requestFocus()
							}
						}
				} else {
					Modifier
				}
			),
		enabled = enabled,
		colors = IconButtonDefaults.colors(
			disabledContainerColor = Color.White.copy(alpha = 0.16f),
			disabledContentColor = Color.White.copy(alpha = 0.38f),
		),
		tooltip = tooltip,
	) {
		AnimatedContent(playState) { playState ->
			when (playState) {
				PlayState.PLAYING,
				PlayState.BUFFERING -> {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_pause),
						contentDescription = tooltip,
					)
				}

				PlayState.STOPPED,
				PlayState.PAUSED,
				PlayState.ERROR -> {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_play),
						contentDescription = tooltip,
					)
				}
			}
		}
	}
}

@Composable
private fun StopButton(
	onClick: () -> Unit,
) {
	val tooltip = stringResource(R.string.lbl_stop)
	IconButton(
		onClick = onClick,
		tooltip = tooltip,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_stop),
			contentDescription = tooltip,
		)
	}
}

@Composable
private fun RewindButton(
	playbackManager: PlaybackManager,
	enabled: Boolean,
	onPreview: () -> Unit,
) {
	val tooltip = stringResource(R.string.rewind)
	IconButton(
		onClick = onPreview,
		enabled = enabled,
		tooltip = tooltip,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_rewind),
			contentDescription = tooltip,
		)
	}
}

@Composable
private fun FastForwardButton(
	playbackManager: PlaybackManager,
	enabled: Boolean,
	onPreview: () -> Unit,
) {
	val tooltip = stringResource(R.string.fast_forward)
	IconButton(
		onClick = onPreview,
		enabled = enabled,
		tooltip = tooltip,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_fast_forward),
			contentDescription = tooltip,
		)
	}
}

@Composable
private fun PreviousEntryButton(
	enabled: Boolean,
	onClick: () -> Unit,
) {
	val tooltip = stringResource(R.string.lbl_prev_item)

	IconButton(
		enabled = enabled,
		onClick = onClick,
		tooltip = tooltip,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_previous),
			contentDescription = tooltip,
		)
	}
}

@Composable
private fun NextEntryButton(
	enabled: Boolean,
	onClick: () -> Unit,
) {
	val tooltip = stringResource(R.string.lbl_next_item)

	IconButton(
		enabled = enabled,
		onClick = onClick,
		tooltip = tooltip,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_next),
			contentDescription = tooltip,
		)
	}
}

private fun Duration.formatted(includeHours: Boolean): String {
	val totalSeconds = toInt(DurationUnit.SECONDS)
	val hours = totalSeconds / 3600
	val minutes = (totalSeconds % 3600) / 60
	val seconds = totalSeconds % 60

	return if (includeHours) "%02d:%02d:%02d".format(hours, minutes, seconds)
	else "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun PositionText(
	playbackManager: PlaybackManager,
	position: Duration? = null,
	liveTvProgramTimeline: LiveTvProgramTimeline? = null,
	liveTvProgramPosition: Duration = Duration.ZERO,
) {
	val context = LocalContext.current
	val timeFormatter = remember(context) { context.getTimeFormatter() }

	if (liveTvProgramTimeline != null) {
		val includeHours = liveTvProgramTimeline.duration.inWholeMinutes >= 60
		val activeFormatted = liveTvProgramPosition
			.coerceIn(Duration.ZERO, liveTvProgramTimeline.duration)
			.formatted(includeHours)
		val durationFormatted = liveTvProgramTimeline.duration.formatted(includeHours)
		val endsText = stringResource(
			R.string.lbl_playback_control_ends,
			timeFormatter.format(liveTvProgramTimeline.end),
		)

		PositionTimeText(
			positionText = "$activeFormatted / $durationFormatted",
			endsText = endsText,
		)
		return
	}

	val positionInfo by rememberPlayerPositionInfo(playbackManager, precision = 1.seconds)
	val speed by playbackManager.state.speed.collectAsState()
	if (positionInfo.duration == Duration.ZERO) return

	val includeHours = positionInfo.duration.inWholeMinutes >= 60
	val activePosition = (position ?: positionInfo.active)
		.coerceIn(Duration.ZERO, positionInfo.duration)
	val activeFormatted = activePosition.formatted(includeHours)
	val durationFormatted = positionInfo.duration.formatted(includeHours)
	val remaining = (positionInfo.duration - activePosition).coerceAtLeast(Duration.ZERO)
	val finishTime = finishTimeFor(remaining, speed)
	val endsText = stringResource(
		R.string.lbl_playback_control_ends,
		timeFormatter.format(finishTime),
	)

	PositionTimeText(
		positionText = "$activeFormatted / $durationFormatted",
		endsText = endsText,
	)
}

@Composable
private fun PositionTimeText(
	positionText: String,
	endsText: String,
) {
	Column(
		horizontalAlignment = Alignment.End,
	) {
		Text(
			text = positionText,
			style = LocalTextStyle.current.copy(color = Color.White),
			softWrap = false,
			maxLines = 1,
			overflow = TextOverflow.Clip,
		)
		Text(
			text = endsText,
			style = JellyfinTheme.typography.listCaption.copy(
				color = Color.White.copy(alpha = 0.72f),
			),
			softWrap = false,
			maxLines = 1,
			overflow = TextOverflow.Clip,
		)
	}
}

private fun finishTimeFor(
	remaining: Duration,
	speed: Float,
): LocalDateTime {
	val effectiveSpeed = speed
		.takeIf { it > 0f }
		?.toDouble()
		?: DefaultPlaybackSpeed
	val realTimeLeftMs = (remaining.inWholeMilliseconds.toDouble() / effectiveSpeed).roundToLong()

	return LocalDateTime.now().plus(realTimeLeftMs, ChronoUnit.MILLIS)
}

@Composable
private fun TimelineSeekbar(
	playbackManager: PlaybackManager,
	position: Duration? = null,
	markers: List<Duration> = emptyList(),
	liveTvProgramTimeline: LiveTvProgramTimeline? = null,
	liveTvProgramPosition: Duration = Duration.ZERO,
	onPreviewSeek: ((Duration?) -> Unit)? = null,
	onSeek: ((Duration) -> Unit)? = null,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
) {
	if (liveTvProgramTimeline != null) {
		Seekbar(
			progress = liveTvProgramPosition.coerceIn(Duration.ZERO, liveTvProgramTimeline.duration),
			duration = liveTvProgramTimeline.duration,
			buffer = Duration.ZERO,
			enabled = false,
			modifier = modifier,
		)
	} else {
		PlayerSeekbar(
			playbackManager = playbackManager,
			progress = position,
			markers = markers,
			onPreviewSeek = onPreviewSeek,
			onSeek = onSeek,
			enabled = enabled,
			modifier = modifier,
		)
	}
}

@Composable
fun PlaybackInfoButton(
	onClick: () -> Unit,
) {
	val tooltip = stringResource(R.string.playback_info)
	IconButton(
		onClick = onClick,
		tooltip = tooltip,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_info),
			contentDescription = tooltip,
		)
	}
}
