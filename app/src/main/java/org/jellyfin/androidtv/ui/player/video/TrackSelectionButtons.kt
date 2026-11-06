package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
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
import org.jellyfin.androidtv.ui.livetv.LiveTvTrackCache
import org.jellyfin.androidtv.ui.base.popover.Popover
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.overlay.action.formatSubtitleOffsetSeconds
import org.jellyfin.androidtv.util.TrackSelectionResolver
import org.jellyfin.androidtv.util.sdk.isLiveTv
import org.jellyfin.playback.jellyfin.livetv.liveTvChannelId
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.PlayerTrack
import org.jellyfin.playback.core.backend.TrackType
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.mediaStreamFlow
import org.jellyfin.playback.core.model.isActivePlayback
import org.jellyfin.playback.core.queue.queue
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

private val POPOVER_VERTICAL_OFFSET = 5.dp

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
		IconButton(
			onClick = {
				refreshTick++
				expanded = true
			},
			tooltip = tooltip,
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_select_audio),
				contentDescription = tooltip,
			)
		}

		TrackSelectionPopover(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			tracks = availableTracks,
			title = stringResource(R.string.lbl_audio_track),
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
	var refreshTick by remember { mutableStateOf(0) }

	val availableTracks = remember(currentStream, refreshTick) {
		trackBackend.getAvailableTracks(TrackType.SUBTITLE)
	}

	if (availableTracks.isEmpty()) return

	Box {
		val tooltip = stringResource(R.string.lbl_subtitle_track)
		IconButton(
			onClick = {
				refreshTick++
				expanded = true
			},
			tooltip = tooltip,
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_select_subtitle),
				contentDescription = tooltip,
			)
		}

		TrackSelectionPopover(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			tracks = availableTracks,
			title = stringResource(R.string.lbl_subtitle_track),
			showNoneOption = true,
			beforeTracks = {
				SubtitleOffsetControls(playbackManager)
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
				expanded = false
			},
		)
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
	showNoneOption: Boolean = false,
	beforeTracks: @Composable () -> Unit = {},
	onTrackSelected: (PlayerTrack?) -> Unit,
) {
	Popover(
		expanded = expanded,
		onDismissRequest = onDismissRequest,
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, -POPOVER_VERTICAL_OFFSET),
	) {
		Column(
			modifier = Modifier
				.padding(horizontal = 6.dp, vertical = 6.dp)
				.widthIn(min = 160.dp, max = 360.dp)
				.heightIn(max = 300.dp)
				.verticalScroll(rememberScrollState())
		) {
			Text(
				text = title,
				style = JellyfinTheme.typography.listHeader.copy(
					color = JellyfinTheme.colorScheme.listHeader
				),
				fontSize = 13.sp,
				modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
private fun TrackItem(
	label: String,
	isSelected: Boolean,
	onClick: () -> Unit,
) {
	Button(
		onClick = onClick,
		contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
		) {
			// Use Box to reserve space for checkmark even when not selected
			Box(modifier = Modifier.size(18.dp)) {
				if (isSelected) {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_check),
						contentDescription = null,
						modifier = Modifier.size(18.dp),
						// Inherits color from Button's content color (dark on light bg, light on dark bg)
					)
				}
			}
			ProvideTextStyle(JellyfinTheme.typography.listHeadline.copy(fontSize = 13.sp)) {
				Text(
					text = label,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
private fun SubtitleOffsetControls(
	playbackManager: PlaybackManager,
) {
	val subtitleTimingOffset by playbackManager.state.subtitleTimingOffset.collectAsState()
	val subtitleTimingOffsetSupported by playbackManager.state.subtitleTimingOffsetSupported.collectAsState()
	if (!subtitleTimingOffsetSupported) return

	Column(
		verticalArrangement = Arrangement.spacedBy(6.dp),
		modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
				onClick = { playbackManager.state.adjustSubtitleTimingOffset((-500).milliseconds) },
				contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
			) {
				Text(stringResource(R.string.lbl_subtitle_offset_seconds, formatSubtitleOffsetSeconds((-500).milliseconds)), fontSize = 12.sp)
			}
			Button(
				onClick = { playbackManager.state.adjustSubtitleTimingOffset((-100).milliseconds) },
				contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
			) {
				Text(stringResource(R.string.lbl_subtitle_offset_seconds, formatSubtitleOffsetSeconds((-100).milliseconds)), fontSize = 12.sp)
			}
			Button(
				onClick = { playbackManager.state.adjustSubtitleTimingOffset(100.milliseconds) },
				contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
			) {
				Text(stringResource(R.string.lbl_subtitle_offset_seconds, formatSubtitleOffsetSeconds(100.milliseconds)), fontSize = 12.sp)
			}
			Button(
				onClick = { playbackManager.state.adjustSubtitleTimingOffset(500.milliseconds) },
				contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
			) {
				Text(stringResource(R.string.lbl_subtitle_offset_seconds, formatSubtitleOffsetSeconds(500.milliseconds)), fontSize = 12.sp)
			}
			Button(
				onClick = { playbackManager.state.resetSubtitleTimingOffset() },
				contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
			) {
				Text(stringResource(R.string.lbl_reset), fontSize = 12.sp)
			}
		}
	}
}

private val PlayerTrack.displayLabel: String
	get() {
		val languageName = language?.let { code ->
			Locale.forLanguageTag(code).displayLanguage.takeIf { it.isNotBlank() && it != code }
		}

		return buildString {
			when {
				!label.isNullOrBlank() -> append(label)
				languageName != null -> append(languageName)
				!language.isNullOrBlank() -> append(language)
				else -> append("Track ${index + 1}")
			}
		}
	}
