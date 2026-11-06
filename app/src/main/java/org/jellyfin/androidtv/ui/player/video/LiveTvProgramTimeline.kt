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
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.compose.koinInject
import timber.log.Timber
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val LiveTvProgramPositionRefreshInterval = 1.seconds
private val LiveTvProgramRefreshRetryInterval = 30.seconds
private const val LiveTvNextProgramWindowHours = 6L

data class LiveTvProgramTimeline(
	val start: LocalDateTime,
	val end: LocalDateTime,
	val duration: Duration,
	val programName: String?,
	val nextProgram: LiveTvProgramDetails? = null,
) {
	fun positionAt(now: LocalDateTime): Duration =
		durationBetween(start, now).coerceIn(Duration.ZERO, duration)
}

data class LiveTvProgramDetails(
	val start: LocalDateTime,
	val end: LocalDateTime,
	val name: String,
)

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
			val updatedTimeline = withContext(Dispatchers.IO) {
				runCatching {
					api.liveTvProgramTimeline(channelId, timeline)
				}.onFailure { error ->
					Timber.w(error, "Failed to refresh Live TV current program")
				}.getOrNull()
			}

			if (updatedTimeline != null) timeline = updatedTimeline
			delay(timeline.refreshDelay())
			if (updatedTimeline == null || !updatedTimeline.end.isAfter(LocalDateTime.now())) {
				delay(LiveTvProgramRefreshRetryInterval)
			}
		}
	}

	return timeline
}

private suspend fun ApiClient.liveTvProgramTimeline(
	channelId: UUID,
	fallback: LiveTvProgramTimeline?,
): LiveTvProgramTimeline? {
	val currentTimeline = liveTvApi.getChannel(channelId).content.liveTvProgramTimeline()
		?: fallback
		?: return null

	val nextProgram = runCatching {
		liveTvApi.getLiveTvPrograms(
			channelIds = listOf(channelId),
			enableImages = false,
			sortBy = setOf(ItemSortBy.START_DATE),
			sortOrder = setOf(SortOrder.ASCENDING),
			maxStartDate = currentTimeline.end.plusHours(LiveTvNextProgramWindowHours),
			minEndDate = currentTimeline.end.minusSeconds(1),
			enableTotalRecordCount = false,
		).content.items.nextProgramAfter(currentTimeline)
	}.onFailure { error ->
		Timber.w(error, "Failed to load Live TV next program")
	}.getOrNull()

	return currentTimeline.copy(nextProgram = nextProgram)
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

private fun Collection<BaseItemDto>.nextProgramAfter(timeline: LiveTvProgramTimeline): LiveTvProgramDetails? =
	sortedBy { program -> program.startDate ?: LocalDateTime.MIN }
		.asSequence()
		.mapNotNull { program -> program.liveTvProgramDetails() }
		.firstOrNull { program -> !program.start.isBefore(timeline.end) }

private fun BaseItemDto.liveTvProgramDetails(): LiveTvProgramDetails? {
	val start = startDate ?: return null
	val duration = runTimeTicks?.ticks
	val end = endDate
		?: duration?.let { start.plus(it.inWholeMilliseconds, ChronoUnit.MILLIS) }
		?: return null
	val name = name?.takeUnless { value -> value.isBlank() } ?: return null

	if (!end.isAfter(start)) return null

	return LiveTvProgramDetails(
		start = start,
		end = end,
		name = name,
	)
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
