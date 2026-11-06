package org.jellyfin.androidtv.ui

import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.util.getActivity
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.SeriesTimerInfoDto
import org.jellyfin.sdk.model.api.TimerInfoDto
import org.koin.android.ext.android.inject
import java.util.UUID

fun BaseItemDto.copyWithTimerId(
	timerId: String?,
) = copy(
	timerId = timerId,
)

fun BaseItemDto.copyWithSeriesTimerId(
	seriesTimerId: String?,
) = copy(
	seriesTimerId = seriesTimerId,
)

fun LiveProgramDetailPopup.cancelTimer(
	timerId: String,
	callback: () -> Unit,
) {
	val api by mContext.getActivity()!!.inject<ApiClient>()

	lifecycle.coroutineScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.cancelTimer(timerId)
			}
		}.onSuccess {
			callback()
		}
	}
}

fun LiveProgramDetailPopup.cancelSeriesTimer(
	seriesTimerId: String,
	callback: () -> Unit,
) {
	val api by mContext.getActivity()!!.inject<ApiClient>()

	lifecycle.coroutineScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.cancelSeriesTimer(seriesTimerId)
			}
		}.onSuccess {
			callback()
		}
	}
}

fun SeriesTimerInfoDto.asTimerInfoDto() = TimerInfoDto(
	id = id,
	type = type,
	serverId = serverId,
	externalId = externalId,
	channelId = channelId,
	externalChannelId = externalChannelId,
	channelName = channelName,
	channelPrimaryImageTag = channelPrimaryImageTag,
	programId = programId,
	externalProgramId = externalProgramId,
	name = name,
	overview = overview,
	startDate = startDate,
	endDate = endDate,
	serviceName = serviceName,
	priority = priority,
	prePaddingSeconds = prePaddingSeconds,
	postPaddingSeconds = postPaddingSeconds,
	isPrePaddingRequired = isPrePaddingRequired,
	parentBackdropItemId = parentBackdropItemId,
	parentBackdropImageTags = parentBackdropImageTags,
	isPostPaddingRequired = isPostPaddingRequired,
	keepUntil = keepUntil,
)

fun LiveProgramDetailPopup.recordProgram(
	programId: UUID,
	callback: (program: BaseItemDto) -> Unit,
) {
	val api by mContext.getActivity()!!.inject<ApiClient>()

	lifecycle.coroutineScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				val seriesTimer by api.liveTvApi.getDefaultTimer(programId.toString())
				val timer = seriesTimer.asTimerInfoDto()
				api.liveTvApi.createTimer(timer)
				api.liveTvApi.getProgram(programId.toString()).content
			}
		}.onSuccess { program ->
			callback(program)
		}
	}
}

fun LiveProgramDetailPopup.recordSeries(
	programId: UUID,
	callback: (program: BaseItemDto) -> Unit,
) {
	val api by mContext.getActivity()!!.inject<ApiClient>()

	lifecycle.coroutineScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				val timer by api.liveTvApi.getDefaultTimer(programId.toString())
				api.liveTvApi.createSeriesTimer(timer)
				api.liveTvApi.getProgram(programId.toString()).content
			}
		}.onSuccess { program ->
			callback(program)
		}
	}
}

fun LiveProgramDetailPopup.getSeriesTimer(
	seriesTimerId: String,
	callback: (seriesTimer: SeriesTimerInfoDto) -> Unit,
) {
	val api by mContext.getActivity()!!.inject<ApiClient>()

	lifecycle.coroutineScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getSeriesTimer(seriesTimerId).content
			}
		}.onSuccess { seriesTimer ->
			callback(seriesTimer)
		}
	}
}

fun LiveProgramDetailPopup.setFavorite(
	itemId: UUID,
	favorite: Boolean,
	callback: () -> Unit,
) {
	val itemMutationRepository by mContext.getActivity()!!.inject<ItemMutationRepository>()

	lifecycle.coroutineScope.launch {
		runCatching {
			itemMutationRepository.setFavorite(
				item = itemId,
				favorite = favorite,
			)
		}.onSuccess {
			callback()
		}
	}
}
