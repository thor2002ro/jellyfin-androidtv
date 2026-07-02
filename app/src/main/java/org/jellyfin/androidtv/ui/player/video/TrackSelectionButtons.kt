package org.jellyfin.androidtv.ui.player.video

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.livetv.LiveTvTrackCache
import org.jellyfin.androidtv.ui.base.popover.Popover
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.overlay.action.formatSubtitleOffsetSeconds
import org.jellyfin.androidtv.util.TrackSelectionResolver
import org.jellyfin.androidtv.util.sdk.isLiveTv
import org.jellyfin.androidtv.util.sdk.liveTvChannelId
import org.jellyfin.androidtv.util.toIso2LanguageCodeOrNull
import org.jellyfin.androidtv.util.toIso2LanguageDisplayOrSelf
import org.jellyfin.androidtv.util.withoutUndeterminedLanguagePrefix
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.PlayerTrack
import org.jellyfin.playback.core.backend.TrackType
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.mediaStreamFlow
import org.jellyfin.playback.core.model.isActivePlayback
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.media3.exoplayer.mapping.getFfmpegSubtitleMimeType
import org.jellyfin.playback.media3.exoplayer.subtitle.isSubtitleTimingOffsetSupported
import org.jellyfin.playback.jellyfin.playsession.PlaySessionService
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

private val SUBTITLE_OFFSET_STEP_SMALL = 100.milliseconds
private val SUBTITLE_OFFSET_STEP_LARGE = 500.milliseconds

@Composable
fun AudioTrackButton(
	playbackManager: PlaybackManager,
	videoQueueManager: VideoQueueManager = koinInject(),
) {
	val trackBackend = playbackManager.trackSelection ?: return
	val currentStream = currentMediaStream(playbackManager)
	val coroutineScope = rememberCoroutineScope()

	var expanded by remember { mutableStateOf(false) }
	var refreshTick by remember { mutableStateOf(0) }

	val availableTracks = remember(currentStream, refreshTick) {
		trackBackend.getAvailableTracks(TrackType.AUDIO)
	}

	if (availableTracks.size < 2) return

	Box {
		val tooltip = stringResource(R.string.lbl_audio_track)
		val icon = ImageVector.vectorResource(R.drawable.ic_select_audio)
		IconButton(
			onClick = {
				refreshTick++
				expanded = true
			},
			tooltip = tooltip,
		) {
			Icon(
				imageVector = icon,
				contentDescription = tooltip,
			)
		}

		TrackSelectionPopover(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			tracks = availableTracks,
			title = stringResource(R.string.lbl_audio_track),
			icon = icon,
			onTrackSelected = { track ->
				track?.let {
					val streamIndex = it.streamIndex ?: it.index
					playbackManager.saveSelectedAudioTrack(videoQueueManager, streamIndex)
					playbackManager.getService<PlaySessionService>()
						?.setSelectedStreamIndexes(audioStreamIndex = streamIndex)

					if (trackBackend.selectTrack(TrackType.AUDIO, it.index)) {
						refreshTick++
					} else {
						reloadCurrentMediaStreamAfterTrackSelection(playbackManager, coroutineScope) {
							refreshTick++
						}
					}
				}
				expanded = false
			},
		)
	}
}

@Composable
fun SubtitleTrackButton(
	playbackManager: PlaybackManager,
	videoQueueManager: VideoQueueManager = koinInject(),
) {
	val trackBackend = playbackManager.trackSelection ?: return
	val currentStream = currentMediaStream(playbackManager)
	val coroutineScope = rememberCoroutineScope()

	var expanded by remember { mutableStateOf(false) }
	var offsetControlsExpanded by remember { mutableStateOf(false) }
	var refreshTick by remember { mutableStateOf(0) }

	val availableTracks = remember(currentStream, refreshTick) {
		trackBackend.getAvailableTracks(TrackType.SUBTITLE)
	}
	val hasOffsetCapableSubtitle = remember(availableTracks) {
		availableTracks.any(PlayerTrack::supportsSubtitleTimingOffset)
	}
	val subtitleTimingOffsetSupported by playbackManager.state.subtitleTimingOffsetSupported.collectAsState()

	if (availableTracks.isEmpty()) return

	Box {
		val tooltip = stringResource(R.string.lbl_subtitle_track)
		val icon = ImageVector.vectorResource(R.drawable.ic_select_subtitle)
		IconButton(
			onClick = {
				refreshTick++
				offsetControlsExpanded = false
				expanded = true
			},
			tooltip = tooltip,
		) {
			Icon(
				imageVector = icon,
				contentDescription = tooltip,
			)
		}

		TrackSelectionPopover(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			tracks = availableTracks,
			title = stringResource(R.string.lbl_subtitle_track),
			icon = icon,
			showNoneOption = true,
			beforeTracks = {
				if (hasOffsetCapableSubtitle) {
					SubtitleOffsetTrackItem(
						playbackManager = playbackManager,
						expanded = offsetControlsExpanded,
						enabled = subtitleTimingOffsetSupported,
						onClick = {
							if (subtitleTimingOffsetSupported) {
								offsetControlsExpanded = !offsetControlsExpanded
							}
						},
					)
				}
			},
			onTrackSelected = { track ->
				val trackIndex = track?.index ?: -1
				val streamIndex = track?.streamIndex ?: -1
				playbackManager.saveSelectedSubtitleTrack(videoQueueManager, streamIndex)
				playbackManager.getService<PlaySessionService>()
					?.setSelectedStreamIndexes(subtitleStreamIndex = streamIndex)

				if (trackBackend.selectTrack(TrackType.SUBTITLE, trackIndex)) {
					refreshTick++
				} else {
					reloadCurrentMediaStreamAfterTrackSelection(playbackManager, coroutineScope) {
						refreshTick++
					}
				}
				offsetControlsExpanded = false
				expanded = false
			},
		)
	}
}

@Composable
fun SubtitleOffsetButton(
	playbackManager: PlaybackManager,
) {
	val subtitleTimingOffsetSupported by playbackManager.state.subtitleTimingOffsetSupported.collectAsState()
	val popupOffsetY = dimensionResource(R.dimen.player_popup_menu_offset_y)
	val popupMinWidth = dimensionResource(R.dimen.player_popup_menu_wide_min_width)
	val popupMaxWidth = dimensionResource(R.dimen.player_popup_menu_wide_max_width)

	var expanded by remember { mutableStateOf(false) }

	Box {
		val tooltip = stringResource(R.string.lbl_subtitle_offset)
		IconButton(
			onClick = { if (subtitleTimingOffsetSupported) expanded = true },
			enabled = subtitleTimingOffsetSupported,
			tooltip = tooltip,
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_time),
				contentDescription = tooltip,
			)
		}

		Popover(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			alignment = Alignment.TopCenter,
			offset = DpOffset(0.dp, -popupOffsetY),
		) {
			SubtitleOffsetControls(
				playbackManager = playbackManager,
				modifier = Modifier
					.widthIn(min = popupMinWidth, max = popupMaxWidth)
					.onPreviewKeyEvent { event ->
						handleSubtitleOffsetKeyEvent(
							keyEvent = event.nativeKeyEvent,
							playbackManager = playbackManager,
						)
					},
			)
		}
	}
}

@Composable
private fun currentMediaStream(playbackManager: PlaybackManager): PlayableMediaStream? {
	val entry by playbackManager.queue.entry.collectAsState()
	val currentEntry = entry ?: return null
	val mediaStream by currentEntry.mediaStreamFlow.collectAsState(initial = currentEntry.mediaStream)
	return mediaStream
}

private fun PlaybackManager.saveSelectedAudioTrack(
	videoQueueManager: VideoQueueManager,
	streamIndex: Int,
) {
	val entry = queue.entry.value
	val item = entry?.baseItem ?: return
	val mediaSource = item.findMediaSource(entry.mediaSourceId)
	TrackSelectionResolver.storeSelectedAudioTrack(item, mediaSource, videoQueueManager, streamIndex)
	LiveTvTrackCache.updateSelectedAudioTrack(item.liveTvChannelId(), streamIndex)
}

private fun PlaybackManager.saveSelectedSubtitleTrack(
	videoQueueManager: VideoQueueManager,
	streamIndex: Int,
) {
	val entry = queue.entry.value
	val item = entry?.baseItem ?: return
	val mediaSource = item.findMediaSource(entry.mediaSourceId)
	TrackSelectionResolver.storeSelectedSubtitleTrack(item, mediaSource, videoQueueManager, streamIndex)
	LiveTvTrackCache.updateSelectedSubtitleTrack(item.liveTvChannelId(), streamIndex)
}

private fun BaseItemDto.findMediaSource(mediaSourceId: String?) = mediaSources
	?.firstOrNull { mediaSource -> mediaSourceId == null || mediaSource.id == mediaSourceId }
	?: mediaSources
		?.firstOrNull()

private fun reloadCurrentMediaStreamAfterTrackSelection(
	playbackManager: PlaybackManager,
	coroutineScope: CoroutineScope,
	onFinished: () -> Unit,
) {
	val isLiveTv = playbackManager.queue.entry.value?.baseItem?.isLiveTv() == true
	val position = playbackManager.state.positionInfo.active.takeUnless { isLiveTv }
	val playWhenReady = playbackManager.state.playState.value.isActivePlayback

	coroutineScope.launch {
		try {
			val reloaded = playbackManager.reloadCurrentMediaStream(
				position = position,
				playWhenReady = playWhenReady,
			)
			if (!reloaded) Timber.w("Unable to reload stream after track selection")
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			Timber.e(error, "Failed to reload stream after track selection")
		} finally {
			onFinished()
		}
	}
}

@Composable
@Suppress("LongParameterList")
private fun TrackSelectionPopover(
	expanded: Boolean,
	onDismissRequest: () -> Unit,
	tracks: List<PlayerTrack>,
	title: String,
	icon: ImageVector,
	showNoneOption: Boolean = false,
	beforeTracks: @Composable () -> Unit = {},
	onTrackSelected: (PlayerTrack?) -> Unit,
) {
	val popupOffsetY = dimensionResource(R.dimen.player_popup_menu_offset_y)
	val popupMinWidth = dimensionResource(R.dimen.player_popup_menu_standard_min_width)
	val popupMaxWidth = dimensionResource(R.dimen.player_popup_menu_standard_max_width)
	val popupMaxHeight = dimensionResource(R.dimen.player_popup_menu_max_height)
	val popupPaddingHorizontal = dimensionResource(R.dimen.player_popup_menu_padding_horizontal)
	val popupPaddingVertical = dimensionResource(R.dimen.player_popup_menu_padding_vertical)

	Popover(
		expanded = expanded,
		onDismissRequest = onDismissRequest,
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, -popupOffsetY),
	) {
		Column(
			verticalArrangement = Arrangement.spacedBy(4.dp),
			modifier = Modifier
				.padding(horizontal = popupPaddingHorizontal, vertical = popupPaddingVertical)
				.widthIn(min = popupMinWidth, max = popupMaxWidth)
				.heightIn(max = popupMaxHeight)
				.verticalScroll(rememberScrollState())
		) {
			TrackSelectionHeader(
				icon = icon,
				title = title,
			)

			beforeTracks()

			if (showNoneOption) {
				TrackItem(
					label = stringResource(R.string.lbl_none),
					isSelected = tracks.none { it.isSelected },
					onClick = { onTrackSelected(null) },
				)
			}

			tracks.forEach { track ->
				TrackItem(
					label = track.displayLabel,
					isSelected = track.isSelected,
					onClick = { onTrackSelected(track) },
				)
			}
		}
	}
}

@Composable
private fun TrackSelectionHeader(
	icon: ImageVector,
	title: String,
) {
	ProvideTextStyle(
		JellyfinTheme.typography.listHeader.copy(
			color = JellyfinTheme.colorScheme.listHeader,
			fontSize = 13.sp,
		)
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(10.dp),
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 6.dp),
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				modifier = Modifier.size(20.dp),
			)
			Text(
				text = title,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
private fun TrackItem(
	label: String,
	isSelected: Boolean,
	onClick: () -> Unit,
) {
	val itemMinHeight = dimensionResource(R.dimen.player_popup_menu_item_min_height)

	ListButton(
		onClick = onClick,
		headingContent = {
			Text(
				text = label,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		},
		leadingContent = {
			RadioButton(checked = isSelected)
		},
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = itemMinHeight),
	)
}

@Composable
private fun SubtitleOffsetTrackItem(
	playbackManager: PlaybackManager,
	expanded: Boolean,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	val subtitleTimingOffset by playbackManager.state.subtitleTimingOffset.collectAsState()
	val itemMinHeight = dimensionResource(R.dimen.player_popup_menu_item_min_height)
	val popupMinWidth = dimensionResource(R.dimen.player_popup_menu_wide_min_width)
	val popupMaxWidth = dimensionResource(R.dimen.player_popup_menu_wide_max_width)
	val label = if (subtitleTimingOffset == kotlin.time.Duration.ZERO) {
		stringResource(R.string.lbl_subtitle_offset)
	} else {
		stringResource(
			R.string.lbl_subtitle_offset_current,
			stringResource(R.string.lbl_subtitle_offset_seconds, formatSubtitleOffsetSeconds(subtitleTimingOffset)),
		)
	}

	ListButton(
		onClick = onClick,
		enabled = enabled,
		headingContent = {
			Text(
				text = label,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		},
		leadingContent = {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_time),
				contentDescription = null,
				modifier = Modifier.size(20.dp),
			)
		},
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = itemMinHeight),
	)

	if (expanded) {
		SubtitleOffsetControls(
			playbackManager = playbackManager,
			modifier = Modifier
				.widthIn(min = popupMinWidth, max = popupMaxWidth)
				.onPreviewKeyEvent { event ->
					handleSubtitleOffsetKeyEvent(
						keyEvent = event.nativeKeyEvent,
						playbackManager = playbackManager,
					)
				},
		)
	}
}

@Composable
private fun SubtitleOffsetControls(
	playbackManager: PlaybackManager,
	modifier: Modifier = Modifier,
) {
	val subtitleTimingOffset by playbackManager.state.subtitleTimingOffset.collectAsState()

	Column(
		verticalArrangement = Arrangement.spacedBy(6.dp),
		modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
	) {
		Text(
			text = stringResource(
				R.string.lbl_subtitle_offset_current,
				stringResource(R.string.lbl_subtitle_offset_seconds, formatSubtitleOffsetSeconds(subtitleTimingOffset))
			),
			style = LocalTextStyle.current.copy(fontSize = 12.sp),
		)

		Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			Button(
				onClick = { playbackManager.state.adjustSubtitleTimingOffset(-SUBTITLE_OFFSET_STEP_LARGE) },
				contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
			) {
				Text(stringResource(R.string.lbl_subtitle_offset_seconds, formatSubtitleOffsetSeconds(-SUBTITLE_OFFSET_STEP_LARGE)), fontSize = 12.sp)
			}
			Button(
				onClick = { playbackManager.state.adjustSubtitleTimingOffset(-SUBTITLE_OFFSET_STEP_SMALL) },
				contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
			) {
				Text(stringResource(R.string.lbl_subtitle_offset_seconds, formatSubtitleOffsetSeconds(-SUBTITLE_OFFSET_STEP_SMALL)), fontSize = 12.sp)
			}
			Button(
				onClick = { playbackManager.state.adjustSubtitleTimingOffset(SUBTITLE_OFFSET_STEP_SMALL) },
				contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
			) {
				Text(stringResource(R.string.lbl_subtitle_offset_seconds, formatSubtitleOffsetSeconds(SUBTITLE_OFFSET_STEP_SMALL)), fontSize = 12.sp)
			}
			Button(
				onClick = { playbackManager.state.adjustSubtitleTimingOffset(SUBTITLE_OFFSET_STEP_LARGE) },
				contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
			) {
				Text(stringResource(R.string.lbl_subtitle_offset_seconds, formatSubtitleOffsetSeconds(SUBTITLE_OFFSET_STEP_LARGE)), fontSize = 12.sp)
			}
		}
		Button(
			onClick = { playbackManager.state.resetSubtitleTimingOffset() },
			contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
			modifier = Modifier.fillMaxWidth(),
		) {
			Text(stringResource(R.string.lbl_reset), fontSize = 12.sp)
		}
	}
}

private fun handleSubtitleOffsetKeyEvent(
	keyEvent: KeyEvent,
	playbackManager: PlaybackManager,
): Boolean = when (keyEvent.keyCode) {
	KeyEvent.KEYCODE_DPAD_UP -> {
		if (keyEvent.action == KeyEvent.ACTION_DOWN) {
			playbackManager.state.adjustSubtitleTimingOffset(SUBTITLE_OFFSET_STEP_SMALL)
		}
		true
	}

	KeyEvent.KEYCODE_DPAD_DOWN -> {
		if (keyEvent.action == KeyEvent.ACTION_DOWN) {
			playbackManager.state.adjustSubtitleTimingOffset(-SUBTITLE_OFFSET_STEP_SMALL)
		}
		true
	}

	else -> false
}

private val PlayerTrack.displayLabel: String
	get() {
		val languageCode = language.toIso2LanguageCodeOrNull()
		val displayLanguage = languageCode ?: language.toIso2LanguageDisplayOrSelf()
		val languageName = languageCode?.let { code ->
			Locale.forLanguageTag(code).displayLanguage.takeIf { it.isNotBlank() && it != code }
		}
		val displayLabel = label.withoutUndeterminedLanguagePrefix()

		return buildString {
			when {
				displayLabel != null -> append(displayLabel)
				languageName != null -> append(languageName)
				!displayLanguage.isNullOrBlank() -> append(displayLanguage)
				else -> append("Track ${index + 1}")
			}
		}
	}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun PlayerTrack.supportsSubtitleTimingOffset(): Boolean {
	if (type != TrackType.SUBTITLE) return false
	val codec = codec ?: return false
	return isSubtitleTimingOffsetSupported(getFfmpegSubtitleMimeType(codec, ""))
}
