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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.getQualityProfiles
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.popover.Popover
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

private val QualityProfilePopoverVerticalOffset = 5.dp

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
	var expanded by remember { mutableStateOf(false) }
	var refreshing by remember { mutableStateOf(false) }

	Box {
		val tooltip = stringResource(R.string.lbl_quality_profile)
		IconButton(
			onClick = {
				selectedQuality = userPreferences[UserPreferences.maxBitrate]
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
			onQualitySelected = selectQuality@{ quality ->
				expanded = false
				if (quality == selectedQuality) return@selectQuality

				val previousQuality = selectedQuality
				selectedQuality = quality
				userPreferences[UserPreferences.maxBitrate] = quality

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
							streamBitrate = streamBitrate,
							forceTranscodingSourceBitrate = queueEntry.forceTranscodingSourceBitrate,
							wasForcedTranscoding = previousForceTranscoding == true,
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
							userPreferences[UserPreferences.maxBitrate] = previousQuality
							entry?.forceTranscoding = previousForceTranscoding
							entry?.forceTranscodingRecoveryAttempts = previousForceTranscodingRecoveryAttempts
							entry?.forceTranscodingSourceBitrate = previousForceTranscodingSourceBitrate
							Timber.w("Unable to reload stream after changing quality profile")
						}
					} catch (error: CancellationException) {
						throw error
					} catch (error: Exception) {
						selectedQuality = previousQuality
						userPreferences[UserPreferences.maxBitrate] = previousQuality
						entry?.forceTranscoding = previousForceTranscoding
						entry?.forceTranscodingRecoveryAttempts = previousForceTranscodingRecoveryAttempts
						entry?.forceTranscodingSourceBitrate = previousForceTranscodingSourceBitrate
						Timber.e(error, "Failed to reload stream after changing quality profile")
					} finally {
						refreshing = false
					}
				}
			},
		)
	}
}

private fun shouldForceLiveTvTranscoding(
	quality: String,
	streamBitrate: Int?,
	forceTranscodingSourceBitrate: Int?,
	wasForcedTranscoding: Boolean,
): Boolean {
	val maxBitrate = quality.toDoubleOrNull()
		?.takeIf { it > 0.0 }
		?.let { (it * 1_000_000).roundToLong() }
		?: return true

	val referenceBitrate = forceTranscodingSourceBitrate
		?: streamBitrate
		?: return !wasForcedTranscoding

	return maxBitrate < referenceBitrate
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
	onQualitySelected: (String) -> Unit,
) {
	Popover(
		expanded = expanded,
		onDismissRequest = onDismissRequest,
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, -QualityProfilePopoverVerticalOffset),
	) {
		Column(
			modifier = Modifier
				.padding(horizontal = 6.dp, vertical = 6.dp)
				.widthIn(min = 160.dp, max = 240.dp)
				.heightIn(max = 300.dp)
				.verticalScroll(rememberScrollState())
		) {
			Text(
				text = stringResource(R.string.lbl_quality_profile),
				style = JellyfinTheme.typography.listHeader.copy(
					color = JellyfinTheme.colorScheme.listHeader
				),
				fontSize = 13.sp,
				modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
			)

			qualityProfiles.forEach { (quality, label) ->
				QualityProfileItem(
					label = label,
					isSelected = quality == selectedQuality,
					onClick = { onQualitySelected(quality) },
				)
			}
		}
	}
}

@Composable
private fun QualityProfileItem(
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
			Box(modifier = Modifier.size(18.dp)) {
				if (isSelected) {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_check),
						contentDescription = null,
						modifier = Modifier.size(18.dp),
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
