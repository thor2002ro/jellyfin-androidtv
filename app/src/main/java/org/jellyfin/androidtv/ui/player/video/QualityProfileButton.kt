package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.getQualityProfiles
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.PlaybackResolution
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.util.sdk.isLiveTv
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.isActivePlayback
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.forceTranscoding
import org.jellyfin.playback.jellyfin.queue.forceTranscodingRecoveryAttempts
import org.jellyfin.playback.jellyfin.queue.forceTranscodingSourceBitrate
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import timber.log.Timber
import kotlin.math.roundToLong

@Composable
fun QualityProfileButton(
	playbackManager: PlaybackManager,
	item: BaseItemDto?,
	userPreferences: UserPreferences = koinInject(),
) {
	if (item == null) return

	val context = LocalContext.current
	val isLiveTv = item.isLiveTv()
	val qualityProfiles = remember(context) { getQualityProfiles(context) }
	val coroutineScope = rememberCoroutineScope()
	var selectedQuality by remember { mutableStateOf(userPreferences[UserPreferences.maxBitrate]) }
	var selectedResolution by remember { mutableStateOf(userPreferences[UserPreferences.maxResolution]) }
	var expanded by remember { mutableStateOf(false) }
	var refreshing by remember { mutableStateOf(false) }

	fun selectProfile(
		quality: String = selectedQuality,
		resolution: PlaybackResolution = selectedResolution,
	) {
		expanded = false
		if (quality == selectedQuality && resolution == selectedResolution) return

		val previousQuality = selectedQuality
		val previousResolution = selectedResolution
		selectedQuality = quality
		selectedResolution = resolution
		userPreferences[UserPreferences.maxBitrate] = quality
		userPreferences[UserPreferences.maxResolution] = resolution

		val entry = playbackManager.queue.entry.value
		val previousForceTranscoding = entry?.forceTranscoding
		val previousForceTranscodingRecoveryAttempts = entry?.forceTranscodingRecoveryAttempts
		val previousForceTranscodingSourceBitrate = entry?.forceTranscodingSourceBitrate
		if (isLiveTv) {
			entry?.let { queueEntry ->
				val stream = queueEntry.mediaStream
				val streamBitrate = stream?.totalBitrate()
				if (stream?.conversionMethod == MediaConversionMethod.None && streamBitrate != null) {
					queueEntry.forceTranscodingSourceBitrate = streamBitrate
				}

				queueEntry.forceTranscoding = shouldForceLiveTvTranscoding(
					quality = quality,
					resolution = resolution,
					stream = stream,
					forceTranscodingSourceBitrate = queueEntry.forceTranscodingSourceBitrate,
					wasForcedTranscoding = previousForceTranscoding == true,
					keepForcedResolution = previousForceTranscoding == true &&
						previousResolution == resolution &&
						resolution != PlaybackResolution.NATIVE,
				)
				queueEntry.forceTranscodingRecoveryAttempts = null
			}
		}
		val position = playbackManager.state.positionInfo.active.takeUnless { isLiveTv }
		val playWhenReady = playbackManager.state.playState.value.isActivePlayback
		coroutineScope.launch {
			refreshing = true
			try {
				val reloaded = playbackManager.reloadCurrentMediaStream(
					position = position,
					playWhenReady = playWhenReady,
				)
				if (!reloaded) {
					selectedQuality = previousQuality
					selectedResolution = previousResolution
					userPreferences[UserPreferences.maxBitrate] = previousQuality
					userPreferences[UserPreferences.maxResolution] = previousResolution
					entry?.forceTranscoding = previousForceTranscoding
					entry?.forceTranscodingRecoveryAttempts = previousForceTranscodingRecoveryAttempts
					entry?.forceTranscodingSourceBitrate = previousForceTranscodingSourceBitrate
					Timber.w("Unable to reload stream after changing quality profile")
				}
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				selectedQuality = previousQuality
				selectedResolution = previousResolution
				userPreferences[UserPreferences.maxBitrate] = previousQuality
				userPreferences[UserPreferences.maxResolution] = previousResolution
				entry?.forceTranscoding = previousForceTranscoding
				entry?.forceTranscodingRecoveryAttempts = previousForceTranscodingRecoveryAttempts
				entry?.forceTranscodingSourceBitrate = previousForceTranscodingSourceBitrate
				Timber.e(error, "Failed to reload stream after changing quality profile")
			} finally {
				refreshing = false
			}
		}
	}

	Box {
		val tooltip = stringResource(R.string.lbl_quality_profile)
		IconButton(
			onClick = {
				selectedQuality = userPreferences[UserPreferences.maxBitrate]
				selectedResolution = userPreferences[UserPreferences.maxResolution]
				expanded = true
			},
			enabled = !refreshing,
			tooltip = tooltip,
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_select_quality),
				contentDescription = tooltip,
			)
		}

		QualityProfilePopover(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			qualityProfiles = qualityProfiles,
			selectedQuality = selectedQuality,
			selectedResolution = selectedResolution,
			onQualitySelected = { quality -> selectProfile(quality = quality) },
			onResolutionSelected = { resolution -> selectProfile(resolution = resolution) },
		)
	}
}

private fun shouldForceLiveTvTranscoding(
	quality: String,
	resolution: PlaybackResolution,
	stream: MediaStream?,
	forceTranscodingSourceBitrate: Int?,
	wasForcedTranscoding: Boolean,
	keepForcedResolution: Boolean,
): Boolean {
	if (keepForcedResolution || stream.exceeds(resolution)) return true

	val maxBitrate = quality.toDoubleOrNull()
		?.takeIf { it > 0.0 }
		?.let { (it * 1_000_000).roundToLong() }
		?: return true

	val referenceBitrate = forceTranscodingSourceBitrate
		?: stream?.totalBitrate()
		?: return !wasForcedTranscoding

	return maxBitrate < referenceBitrate
}

private fun MediaStream?.exceeds(resolution: PlaybackResolution): Boolean {
	val maxWidth = resolution.maxWidth ?: return false
	val maxHeight = resolution.maxHeight ?: return false
	val videoTrack = this?.tracks?.filterIsInstance<MediaStreamVideoTrack>()?.firstOrNull() ?: return false

	return videoTrack.width > maxWidth || videoTrack.height > maxHeight
}

private fun MediaStream.totalBitrate(): Int? = tracks
	.sumOf { track ->
		when (track) {
			is MediaStreamAudioTrack -> track.bitrate.takeIf { it > 0 } ?: 0
			is MediaStreamVideoTrack -> track.bitrate.takeIf { it > 0 } ?: 0
			else -> 0
		}
	}
	.takeIf { it > 0 }

@Composable
private fun QualityProfilePopover(
	expanded: Boolean,
	onDismissRequest: () -> Unit,
	qualityProfiles: Map<String, String>,
	selectedQuality: String,
	selectedResolution: PlaybackResolution,
	onQualitySelected: (String) -> Unit,
	onResolutionSelected: (PlaybackResolution) -> Unit,
) {
	PlayerSelectionPopover(
		expanded = expanded,
		onDismissRequest = onDismissRequest,
		title = stringResource(R.string.lbl_quality_profile),
		wide = true,
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			modifier = Modifier.fillMaxWidth(),
		) {
			Column(modifier = Modifier.weight(1f)) {
				QualityProfileSectionHeader("Resolution")
				PlaybackResolution.entries.forEach { resolution ->
					PlayerSelectionItem(
						label = resolution.label,
						isSelected = resolution == selectedResolution,
						onClick = { onResolutionSelected(resolution) },
						modifier = Modifier.fillMaxWidth(),
					)
				}
			}

			Column(modifier = Modifier.weight(1f)) {
				QualityProfileSectionHeader("Bitrate")
				qualityProfiles.forEach { (quality, label) ->
					PlayerSelectionItem(
						label = label,
						isSelected = quality == selectedQuality,
						onClick = { onQualitySelected(quality) },
						modifier = Modifier.fillMaxWidth(),
					)
				}
			}
		}
	}
}

@Composable
private fun QualityProfileSectionHeader(
	label: String,
) {
	Text(
		text = label,
		style = JellyfinTheme.typography.listHeader.copy(
			color = JellyfinTheme.colorScheme.listHeader
		),
		fontSize = 12.sp,
		modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
	)
}
