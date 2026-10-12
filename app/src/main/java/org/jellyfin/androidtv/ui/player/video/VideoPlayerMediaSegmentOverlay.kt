package org.jellyfin.androidtv.ui.player.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.base.button.ProgressButton
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentAction
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepository
import org.jellyfin.androidtv.ui.playback.segment.rememberSkipPromptProgress
import org.jellyfin.androidtv.util.sdk.end
import org.jellyfin.androidtv.util.sdk.start
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Composable
fun VideoPlayerMediaSegmentOverlay(
	playbackManager: PlaybackManager,
	item: BaseItemDto?,
	onPromptTargetChanged: (Duration?) -> Unit,
	onEndingSkipPromptChanged: (Boolean) -> Unit = {},
	modifier: Modifier = Modifier,
) {
	val mediaSegmentRepository = koinInject<MediaSegmentRepository>()
	val userPreferences = koinInject<UserPreferences>()
	val positionInfo by rememberPlayerPositionInfo(playbackManager, precision = 250.milliseconds)
	var segments by remember(item?.id) { mutableStateOf<List<MediaSegmentDto>>(emptyList()) }
	var handledSkipSegmentKeys by remember(item?.id) { mutableStateOf(emptySet<String>()) }
	var previousPosition by remember(item?.id) { mutableStateOf<Duration?>(null) }

	LaunchedEffect(item?.id) {
		segments = item?.let { mediaSegmentRepository.getSegmentsForItem(it) }.orEmpty()
		handledSkipSegmentKeys = emptySet()
		previousPosition = null
		onPromptTargetChanged(null)
		onEndingSkipPromptChanged(false)
	}

	val position = positionInfo.active

	LaunchedEffect(position, segments) {
		val previous = previousPosition
		val skipSegment = findAutoSkipSegment(
			segments = segments,
			previousPosition = previous,
			position = position,
			handledKeys = handledSkipSegmentKeys,
			actionFor = mediaSegmentRepository::getMediaSegmentAction,
		)

		if (skipSegment != null) {
			handledSkipSegmentKeys = handledSkipSegmentKeys + skipSegment.stableKey
			playbackManager.state.seek(skipSegment.end)
		}

		previousPosition = position
	}

	val promptSegment = remember(position, segments) {
		findPromptSegment(segments, position, mediaSegmentRepository::getMediaSegmentAction)
	}

	val progress = rememberSkipPromptProgress(
		promptKey = promptSegment?.let { item?.id to it.stableKey },
		timeoutSeconds = userPreferences[UserPreferences.skipPromptTimeout],
	)
	val promptVisible = promptSegment != null && progress < 1f

	LaunchedEffect(item?.id, promptSegment?.stableKey, promptVisible) {
		onPromptTargetChanged(promptSegment?.end.takeIf { promptVisible })
		onEndingSkipPromptChanged(promptVisible && promptSegment.type == MediaSegmentType.OUTRO)
	}

	AnimatedVisibility(
		visible = promptVisible,
		enter = fadeIn(),
		exit = fadeOut(),
		modifier = modifier,
	) {
		ProgressButton(
			progress = progress,
			onClick = { promptSegment?.let { playbackManager.state.seek(it.end) } },
			enabled = promptVisible,
			shape = RoundedCornerShape(6.dp),
			colors = ButtonDefaults.colors(
				containerColor = colorResource(R.color.popup_menu_background).copy(alpha = 0.6f),
			),
			contentPadding = PaddingValues(10.dp),
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_control_select),
				contentDescription = null,
			)
			Spacer(Modifier.width(8.dp))

			Text(
				text = stringResource(R.string.segment_action_skip),
				color = colorResource(R.color.button_default_normal_text),
				fontSize = 18.sp,
			)
		}
	}
}

internal val MediaSegmentDto.stableKey: String
	get() = "$type:$startTicks:$endTicks"

internal fun findPromptSegment(
	segments: List<MediaSegmentDto>,
	position: Duration,
	actionFor: (MediaSegmentDto) -> MediaSegmentAction,
): MediaSegmentDto? = segments.firstOrNull { segment ->
	actionFor(segment) == MediaSegmentAction.ASK_TO_SKIP &&
		position >= segment.start &&
		position < segment.end
}

internal fun findAutoSkipSegment(
	segments: List<MediaSegmentDto>,
	previousPosition: Duration?,
	position: Duration,
	handledKeys: Set<String>,
	actionFor: (MediaSegmentDto) -> MediaSegmentAction,
	maximumNaturalAdvance: Duration = 2.seconds,
): MediaSegmentDto? {
	if (previousPosition == null || position < previousPosition || position - previousPosition > maximumNaturalAdvance) return null
	return segments.firstOrNull { segment ->
		actionFor(segment) == MediaSegmentAction.SKIP &&
			segment.stableKey !in handledKeys &&
			previousPosition < segment.start &&
			position >= segment.start &&
			position < segment.end
	}
}
