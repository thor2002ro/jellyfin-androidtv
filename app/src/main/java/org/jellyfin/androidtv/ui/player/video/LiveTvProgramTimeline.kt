package org.jellyfin.androidtv.ui.player.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jellyfin.playback.jellyfin.livetv.liveTvChannelId
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.compose.koinInject
import timber.log.Timber
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val LiveTvProgramPositionRefreshInterval = 1.seconds
private val LiveTvProgramRefreshRetryInterval = 30.seconds

data class LiveTvProgramTimeline(
	val start: LocalDateTime,
	val end: LocalDateTime,
	val duration: Duration,
	val programName: String?,
) {
	fun positionAt(now: LocalDateTime): Duration =
		durationBetween(start, now).coerceIn(Duration.ZERO, duration)
}

@Composable
fun rememberLiveTvProgramTimeline(
	item: BaseItemDto?,
	api: ApiClient = koinInject(),
): LiveTvProgramTimeline? {
	val initialTimeline = item?.liveTvProgramTimeline()
	var timeline by remember(item?.id) { mutableStateOf(initialTimeline) }
	val channelId = item?.liveTvChannelId()

	LaunchedEffect(api, channelId, initialTimeline) {
		timeline = initialTimeline
		if (channelId == null) return@LaunchedEffect

		while (true) {
			delay(timeline.refreshDelay())

			val updatedTimeline = withContext(Dispatchers.IO) {
				runCatching {
					api.liveTvApi.getChannel(channelId).content.liveTvProgramTimeline()
				}.onFailure { error ->
					Timber.w(error, "Failed to refresh Live TV current program")
				}.getOrNull()
			}

			if (updatedTimeline != null) timeline = updatedTimeline
			if (updatedTimeline == null || !updatedTimeline.end.isAfter(LocalDateTime.now())) {
				delay(LiveTvProgramRefreshRetryInterval)
			}
		}
	}

	return timeline
}

@Composable
fun rememberLiveTvProgramPosition(timeline: LiveTvProgramTimeline?): Duration {
	var now by remember { mutableStateOf(LocalDateTime.now()) }

	LaunchedEffect(timeline) {
		while (timeline != null) {
			now = LocalDateTime.now()
			delay(LiveTvProgramPositionRefreshInterval)
		}
	}

	return timeline?.positionAt(now) ?: Duration.ZERO
}

private fun BaseItemDto.liveTvProgramTimeline(): LiveTvProgramTimeline? {
	if (liveTvChannelId() == null) return null

	val program = currentProgram
	val start = program?.startDate ?: startDate ?: premiereDate ?: return null
	val explicitEnd = program?.endDate ?: endDate
	val duration = program?.runTimeTicks?.ticks
		?: runTimeTicks?.ticks
		?: explicitEnd?.let { end -> durationBetween(start, end) }
		?: return null

	if (duration <= Duration.ZERO) return null

	val end = explicitEnd ?: start.plus(duration.inWholeMilliseconds, ChronoUnit.MILLIS)
	return LiveTvProgramTimeline(
		start = start,
		end = end,
		duration = duration,
		programName = program?.name?.takeUnless { name -> name.isBlank() } ?: name.takeIf {
			type == BaseItemKind.PROGRAM ||
				type == BaseItemKind.TV_PROGRAM ||
				type == BaseItemKind.LIVE_TV_PROGRAM
		}?.takeUnless { name -> name.isBlank() },
	)
}

private fun LiveTvProgramTimeline?.refreshDelay(): Duration {
	val now = LocalDateTime.now()
	return this
		?.takeIf { timeline -> timeline.end.isAfter(now) }
		?.let { timeline -> durationBetween(now, timeline.end) + LiveTvProgramPositionRefreshInterval }
		?: LiveTvProgramPositionRefreshInterval
}

private fun durationBetween(
	start: LocalDateTime,
	end: LocalDateTime,
): Duration = ChronoUnit.MILLIS.between(start, end).milliseconds
