package org.jellyfin.androidtv.ui.player.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentAction
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepository
import org.jellyfin.androidtv.util.sdk.end
import org.jellyfin.androidtv.util.sdk.start
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun VideoPlayerMediaSegmentOverlay(
	playbackManager: PlaybackManager,
	item: BaseItemDto?,
	onPromptTargetChanged: (Duration?) -> Unit,
	modifier: Modifier = Modifier,
) {
	val mediaSegmentRepository = koinInject<MediaSegmentRepository>()
	val positionInfo by rememberPlayerPositionInfo(playbackManager, precision = 250.milliseconds)
	var segments by remember(item?.id) { mutableStateOf<List<MediaSegmentDto>>(emptyList()) }
	var handledSkipSegmentKeys by remember(item?.id) { mutableStateOf(emptySet<String>()) }
	var previousPosition by remember(item?.id) { mutableStateOf<Duration?>(null) }

	LaunchedEffect(item?.id) {
		segments = item?.let { mediaSegmentRepository.getSegmentsForItem(it) }.orEmpty()
		handledSkipSegmentKeys = emptySet()
		previousPosition = null
		onPromptTargetChanged(null)
	}

	val position = positionInfo.active

	LaunchedEffect(position, segments) {
		val previous = previousPosition
		val skipSegment = segments.firstOrNull { segment ->
			val key = segment.key
			mediaSegmentRepository.getMediaSegmentAction(segment) == MediaSegmentAction.SKIP &&
				!handledSkipSegmentKeys.contains(key) &&
				previous != null &&
				previous < segment.start &&
				position >= segment.start &&
				position < segment.end
		}

		if (skipSegment != null) {
			handledSkipSegmentKeys = handledSkipSegmentKeys + skipSegment.key
			playbackManager.state.seek(skipSegment.end)
		}

		previousPosition = position
	}

	val promptSegment = remember(position, segments) {
		segments.firstOrNull { segment ->
			mediaSegmentRepository.getMediaSegmentAction(segment) == MediaSegmentAction.ASK_TO_SKIP &&
				position >= segment.start &&
				position < segment.end
		}
	}

	LaunchedEffect(promptSegment?.key) {
		onPromptTargetChanged(promptSegment?.end)
	}

	AnimatedVisibility(
		visible = promptSegment != null,
		enter = fadeIn(),
		exit = fadeOut(),
		modifier = modifier,
	) {
		Row(
			modifier = Modifier
				.clip(RoundedCornerShape(6.dp))
				.background(colorResource(R.color.popup_menu_background).copy(alpha = 0.6f))
				.padding(10.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_control_select),
				contentDescription = null,
			)

			Text(
				text = stringResource(R.string.segment_action_skip),
				color = colorResource(R.color.button_default_normal_text),
				fontSize = 18.sp,
			)
		}
	}
}

private val MediaSegmentDto.key: String
	get() = "$type:$startTicks:$endTicks"
