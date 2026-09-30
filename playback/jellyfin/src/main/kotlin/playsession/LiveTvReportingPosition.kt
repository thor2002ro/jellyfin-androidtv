package org.jellyfin.playback.jellyfin.playsession

import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.jellyfin.livetv.liveTvChannelId
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.extensions.inWholeTicks
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val LIVE_TV_DASHBOARD_TEXT_TARGET_WIDTH_PX = 246
private const val LIVE_TV_DASHBOARD_SPACE_WIDTH_PX = 4
private const val LIVE_TV_REPORTING_PADDING_SPACE = '\u00A0'
private const val LIVE_TV_REPORTING_LINE_SEPARATOR = '\u2028'

internal fun BaseItemDto.getReportingPositionTicks(
	conversionMethod: MediaConversionMethod,
	playerPosition: Duration,
	streamStartedAt: LocalDateTime?,
	now: LocalDateTime = LocalDateTime.now(),
): Long = getReportingPosition(
	conversionMethod = conversionMethod,
	playerPosition = playerPosition,
	streamStartedAt = streamStartedAt,
	now = now,
).inWholeTicks

internal fun BaseItemDto.getReportingPosition(
	conversionMethod: MediaConversionMethod,
	playerPosition: Duration,
	streamStartedAt: LocalDateTime?,
	now: LocalDateTime = LocalDateTime.now(),
): Duration {
	if (!isLiveTv) return playerPosition

	val programStart = startDate ?: premiereDate ?: return playerPosition
	val position = when (conversionMethod) {
		MediaConversionMethod.None -> durationBetween(programStart, now)
		MediaConversionMethod.Remux,
		MediaConversionMethod.Transcode -> {
			val streamStart = streamStartedAt
				?: now.minus(playerPosition.inWholeMilliseconds, ChronoUnit.MILLIS)
			playerPosition + durationBetween(programStart, streamStart)
		}
	}

	return position.coerceAtLeast(Duration.ZERO)
}

internal fun BaseItemDto.getLiveTvReportingItem(
	currentChannel: BaseItemDto? = null,
): BaseItemDto? {
	if (!isLiveTv) return null

	val channel = currentChannel?.takeIf { it.isLiveTv } ?: this
	val program = channel.currentProgram ?: currentProgram ?: takeIf { it.isLiveTvProgram }
	val reportingProgram = program?.copy(
		channelId = program.channelId ?: channel.liveTvChannelId(),
		channelName = program.channelName ?: channel.name ?: channelName,
		currentProgram = null,
	)
	val reportingStart = reportingProgram?.startDate ?: channel.startDate ?: startDate ?: premiereDate
	val reportingEnd = reportingProgram?.endDate ?: channel.endDate ?: endDate
	val reportingRuntimeTicks = program?.runTimeTicks
		?: channel.runTimeTicks
		?: runTimeTicks
		?: reportingStart?.let { start ->
			reportingEnd?.let { end ->
				durationBetween(start, end)
					.coerceAtLeast(Duration.ZERO)
					.inWholeTicks
			}
		}

	return copy(
		name = channel.getLiveTvReportingName(reportingProgram),
		startDate = reportingStart,
		premiereDate = reportingProgram?.premiereDate ?: channel.premiereDate ?: premiereDate,
		endDate = reportingEnd,
		officialRating = reportingProgram?.officialRating ?: channel.officialRating ?: officialRating,
		runTimeTicks = reportingRuntimeTicks,
		programId = reportingProgram?.id?.toString() ?: programId,
		currentProgram = reportingProgram,
	)
}

private fun BaseItemDto.getLiveTvReportingName(program: BaseItemDto?): String? {
	val channelName = name?.takeIf { it.isNotBlank() } ?: channelName
	val programName = program?.name?.takeIf { it.isNotBlank() } ?: return channelName
	val baseChannelName = channelName
		?.removeLiveTvProgramSuffix(programName)

	return when {
		baseChannelName.isNullOrBlank() -> programName
		else -> "$baseChannelName${getLiveTvDashboardWrapPadding(baseChannelName)} $programName"
	}
}

private fun BaseItemDto.getLiveTvDashboardWrapPadding(channelName: String): String {
	val dashboardChannelText = listOfNotNull(
		channelNumber?.takeIf { it.isNotBlank() },
		channelName,
	).joinToString(" ")
	val usedWidth = dashboardChannelText.sumOf { it.estimatedLiveTvDashboardWidthPx() }
	val paddingWidth = (LIVE_TV_DASHBOARD_TEXT_TARGET_WIDTH_PX - usedWidth)
		.coerceAtLeast(LIVE_TV_DASHBOARD_SPACE_WIDTH_PX)
	val paddingCount = ((paddingWidth + LIVE_TV_DASHBOARD_SPACE_WIDTH_PX - 1) / LIVE_TV_DASHBOARD_SPACE_WIDTH_PX)
		.coerceIn(1, 80)

	return LIVE_TV_REPORTING_PADDING_SPACE.toString().repeat(paddingCount)
}

private fun String.removeLiveTvProgramSuffix(programName: String): String {
	if (!endsWith(programName)) return this

	return removeSuffix(programName)
		.trimEnd(' ', '-', '\n', LIVE_TV_REPORTING_LINE_SEPARATOR, LIVE_TV_REPORTING_PADDING_SPACE)
}

private fun Char.estimatedLiveTvDashboardWidthPx() = when {
	this == ' ' || this == LIVE_TV_REPORTING_PADDING_SPACE -> LIVE_TV_DASHBOARD_SPACE_WIDTH_PX
	isDigit() -> 8
	isUpperCase() -> 10
	isLowerCase() -> 8
	else -> 8
}

private val BaseItemDto.isLiveTv
	get() = when (type) {
		BaseItemKind.PROGRAM,
		BaseItemKind.TV_PROGRAM,
		BaseItemKind.LIVE_TV_PROGRAM,
		BaseItemKind.TV_CHANNEL,
		BaseItemKind.LIVE_TV_CHANNEL -> true

		else -> false
	}

private val BaseItemDto.isLiveTvProgram
	get() = when (type) {
		BaseItemKind.PROGRAM,
		BaseItemKind.TV_PROGRAM,
		BaseItemKind.LIVE_TV_PROGRAM -> true

		else -> false
	}

private fun durationBetween(
	start: LocalDateTime,
	end: LocalDateTime,
): Duration = ChronoUnit.MILLIS.between(start, end).milliseconds
