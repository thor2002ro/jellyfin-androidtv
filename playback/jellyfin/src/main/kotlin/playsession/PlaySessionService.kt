package org.jellyfin.playback.jellyfin.playsession

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.backend.PlayerTrack
import org.jellyfin.playback.core.backend.TrackType
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.mediaStreamFlow
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.RepeatMode
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.livetv.liveTvChannelId
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.liveStreamId
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.QueueItem
import timber.log.Timber
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.jellyfin.sdk.model.api.RepeatMode as SdkRepeatMode

class PlaySessionService(
	private val api: ApiClient,
) : PlayerService() {
	private companion object {
		val PROGRESS_REPORTING_INTERVAL = 3.seconds
		val PAUSED_REPORTING_INTERVAL = 15.seconds
		val LIVE_TV_PROGRAM_REFRESH_RETRY_INTERVAL = 30.seconds
	}

	private var selectedAudioStreamIndex: Int? = null
	private var selectedSubtitleStreamIndex: Int? = null
	private var stoppedPlaySessionId: String? = null
	private var activePlaybackKey: String? = null
	private var activeSession: ActivePlaybackSession? = null
	private var progressReportJob: Job? = null
	private var liveTvReportingState: LiveTvReportingState? = null

	@OptIn(ExperimentalCoroutinesApi::class)
	override suspend fun onInitialize() {
		state.playState.onEach { playState ->
			when (playState) {
				PlayState.PLAYING -> {
					sendStreamStart()
					startProgressReports(PROGRESS_REPORTING_INTERVAL)
				}

				PlayState.STOPPED -> {
					stopProgressReports()
					sendActiveStreamStop()
					activePlaybackKey = null
				}

				PlayState.PAUSED -> {
					sendStreamStart()
					startProgressReports(PAUSED_REPORTING_INTERVAL)
				}

				PlayState.ERROR -> {
					stopProgressReports()
					sendActiveStreamStop()
					activePlaybackKey = null
				}
			}
		}.launchIn(coroutineScope)

		manager.queue.entry.onEach { entry ->
			stopActiveSessionIfChanged(entry, entry?.mediaStream)

			selectedAudioStreamIndex = null
			selectedSubtitleStreamIndex = null
			if (entry?.mediaStream?.identifier != stoppedPlaySessionId) stoppedPlaySessionId = null
			activePlaybackKey = null
			liveTvReportingState = null
		}.launchIn(coroutineScope)

		manager.queue.entry
			.filterNotNull()
			.flatMapLatest { entry -> entry.mediaStreamFlow }
			.onEach { stream ->
				stopActiveSessionIfChanged(manager.queue.entry.value, stream)
			}.launchIn(coroutineScope)
	}

	private data class ActivePlaybackSession(
		val playSessionId: String,
		val mediaSourceId: String?,
		val liveStreamId: String?,
		val playlistItemId: String?,
		val queue: List<QueueItem>,
		val item: BaseItemDto,
		val conversionMethod: MediaConversionMethod,
		val streamStartedAt: LocalDateTime,
	)

	private data class LiveTvReportingState(
		val itemId: java.util.UUID,
		val item: BaseItemDto,
		val refreshAt: LocalDateTime,
	)

	private data class PlaybackReportingState(
		val item: BaseItemDto?,
		val positionTicks: Long,
	)

	private val MediaConversionMethod.playMethod
		get() = when (this) {
			MediaConversionMethod.None -> PlayMethod.DIRECT_PLAY
			MediaConversionMethod.Remux -> PlayMethod.DIRECT_STREAM
			MediaConversionMethod.Transcode -> PlayMethod.TRANSCODE
		}

	private val RepeatMode.remoteRepeatMode
		get() = when (this) {
			RepeatMode.NONE -> SdkRepeatMode.REPEAT_NONE
			RepeatMode.REPEAT_ENTRY_ONCE -> SdkRepeatMode.REPEAT_ONE
			RepeatMode.REPEAT_ENTRY_INFINITE -> SdkRepeatMode.REPEAT_ALL
		}

	fun sendUpdateIfActive() {
		coroutineScope.launch { sendStreamUpdate() }
	}

	fun sendStopIfActive() {
		val session = activeSession
		if (session != null) {
			if (stoppedPlaySessionId == session.playSessionId) return

			stoppedPlaySessionId = session.playSessionId
			coroutineScope.launch {
				val reporting = getPlaybackReportingState(
					item = session.item,
					conversionMethod = session.conversionMethod,
					streamStartedAt = session.streamStartedAt,
				)
				reportPlaybackStopped(
					itemId = session.item.id,
					item = reporting.item,
					playSessionId = session.playSessionId,
					mediaSourceId = session.mediaSourceId,
					liveStreamId = session.liveStreamId,
					playlistItemId = session.playlistItemId,
					positionTicks = reporting.positionTicks,
					queue = session.queue,
				)
			}
			return
		}

		val entry = manager.queue.entry.value ?: return
		val stream = entry.mediaStream ?: return
		val item = entry.baseItem ?: return
		if (stoppedPlaySessionId == stream.identifier) return

		stoppedPlaySessionId = stream.identifier
		val queue = manager.queue.entries.value
			.mapNotNull { it.baseItem }
			.map { QueueItem(id = it.id, playlistItemId = it.playlistItemId) }

		coroutineScope.launch {
			val reporting = getPlaybackReportingState(
				item = item,
				conversionMethod = stream.conversionMethod,
				streamStartedAt = null,
			)
			reportPlaybackStopped(
				itemId = item.id,
				item = reporting.item,
				playSessionId = stream.identifier,
				mediaSourceId = entry.mediaSourceId,
				liveStreamId = entry.liveStreamId,
				playlistItemId = item.playlistItemId,
				positionTicks = reporting.positionTicks,
				queue = queue,
			)
		}
	}

	private fun startProgressReports(interval: Duration) {
		progressReportJob?.cancel()
		progressReportJob = coroutineScope.launch {
			while (isActive) {
				delay(interval)
				when (state.playState.value) {
					PlayState.PLAYING,
					PlayState.PAUSED -> sendStreamUpdate()

					PlayState.STOPPED,
					PlayState.ERROR -> break
				}
			}
		}
	}

	private fun stopProgressReports() {
		progressReportJob?.cancel()
		progressReportJob = null
	}

	fun setSelectedStreamIndexes(
		audioStreamIndex: Int? = selectedAudioStreamIndex,
		subtitleStreamIndex: Int? = selectedSubtitleStreamIndex,
	) {
		selectedAudioStreamIndex = audioStreamIndex
		selectedSubtitleStreamIndex = subtitleStreamIndex
		sendUpdateIfActive()
	}

	private suspend fun getSelectedAudioStreamIndex() = withContext(Dispatchers.Main) {
		manager.trackSelection
			?.getAvailableTracks(TrackType.AUDIO)
			?.firstOrNull(PlayerTrack::isSelected)
			?.streamIndex
			?: selectedAudioStreamIndex
	}

	private suspend fun getSelectedSubtitleStreamIndex(): Int? = withContext(Dispatchers.Main) {
		val tracks = manager.trackSelection?.getAvailableTracks(TrackType.SUBTITLE) ?: return@withContext selectedSubtitleStreamIndex
		tracks.firstOrNull(PlayerTrack::isSelected)?.streamIndex ?: -1
	}

	private suspend fun getQueue(): List<QueueItem> {
		// The queues are lazy loaded so we only load a small amount of items to set as queue on the
		// backend.
		return manager.queue
			.peekNext(15)
			.mapNotNull { it.baseItem }
			.map { QueueItem(id = it.id, playlistItemId = it.playlistItemId) }
	}

	private suspend fun sendStreamStart() {
		val entry = manager.queue.entry.value ?: return
		val stream = entry.mediaStream ?: return
		val item = entry.baseItem ?: return
		stopActiveSessionIfChanged(entry, stream)
		val playbackKey = "${item.id}:${stream.identifier}"
		val session = activeSession

		if (
			activePlaybackKey == playbackKey &&
			session != null &&
			session.item.id == item.id &&
			session.playSessionId == stream.identifier
		) {
			sendStreamUpdate()
			return
		}

		if (stoppedPlaySessionId == stream.identifier) stoppedPlaySessionId = null
		val queue = getQueue()
		val streamStartedAt = LocalDateTime.now()
		val playMethod = stream.conversionMethod.playMethod
		val reporting = getPlaybackReportingState(
			item = item,
			conversionMethod = stream.conversionMethod,
			streamStartedAt = streamStartedAt,
			now = streamStartedAt,
		)
		val audioStreamIndex = getSelectedAudioStreamIndex()
		val subtitleStreamIndex = getSelectedSubtitleStreamIndex()
		val repeatMode = state.repeatMode.value.remoteRepeatMode
		val playbackOrder = when (state.playbackOrder.value) {
			org.jellyfin.playback.core.model.PlaybackOrder.DEFAULT -> PlaybackOrder.DEFAULT
			org.jellyfin.playback.core.model.PlaybackOrder.RANDOM -> PlaybackOrder.SHUFFLE
			org.jellyfin.playback.core.model.PlaybackOrder.SHUFFLE -> PlaybackOrder.SHUFFLE
		}
		var reported = false

		runCatching {
			api.playStateApi.reportPlaybackStart(
				PlaybackStartInfo(
					itemId = item.id,
					item = reporting.item,
					playSessionId = stream.identifier,
					mediaSourceId = entry.mediaSourceId,
					liveStreamId = entry.liveStreamId,
					playlistItemId = item.playlistItemId,
					canSeek = true,
					isMuted = state.volume.muted,
					volumeLevel = (state.volume.volume * 100).roundToInt(),
					isPaused = state.playState.value != PlayState.PLAYING,
					aspectRatio = state.videoSize.value.aspectRatio.toString(),
					positionTicks = reporting.positionTicks,
					playMethod = playMethod,
					audioStreamIndex = audioStreamIndex,
					subtitleStreamIndex = subtitleStreamIndex,
					repeatMode = repeatMode,
					nowPlayingQueue = queue,
					playbackOrder = playbackOrder,
				)
			)
		}.onSuccess {
			reported = true
			Timber.i("Reported playback start for ${item.id} session ${stream.identifier}")
		}.onFailure { error -> Timber.w(error, "Failed to send playback start event") }

		@Suppress("DEPRECATION")
		runCatching {
			api.playStateApi.onPlaybackStart(
				itemId = item.id,
				mediaSourceId = entry.mediaSourceId,
				audioStreamIndex = audioStreamIndex,
				subtitleStreamIndex = subtitleStreamIndex,
				playMethod = playMethod,
				liveStreamId = entry.liveStreamId,
				playSessionId = stream.identifier,
				canSeek = true,
			)
		}.onSuccess {
			reported = true
			Timber.i("Reported legacy playback start for ${item.id} session ${stream.identifier}")
		}.onFailure { error -> Timber.w(error, "Failed to send legacy playback start event") }

		if (reported) {
			activePlaybackKey = playbackKey
			activeSession = ActivePlaybackSession(
				playSessionId = stream.identifier,
				mediaSourceId = entry.mediaSourceId,
				liveStreamId = entry.liveStreamId,
				playlistItemId = item.playlistItemId,
				queue = queue,
				item = item,
				conversionMethod = stream.conversionMethod,
				streamStartedAt = streamStartedAt,
			)
		}
	}

	private suspend fun sendStreamUpdate() {
		val entry = manager.queue.entry.value ?: return
		val stream = entry.mediaStream ?: return
		val session = activeSession
		if (session?.playSessionId != stream.identifier) {
			sendStreamStart()
			return
		}
		val reporting = getPlaybackReportingState(
			item = session.item,
			conversionMethod = session.conversionMethod,
			streamStartedAt = session.streamStartedAt,
		)
		val playMethod = stream.conversionMethod.playMethod
		val audioStreamIndex = getSelectedAudioStreamIndex()
		val subtitleStreamIndex = getSelectedSubtitleStreamIndex()
		val repeatMode = state.repeatMode.value.remoteRepeatMode
		val playbackOrder = when (state.playbackOrder.value) {
			org.jellyfin.playback.core.model.PlaybackOrder.DEFAULT -> PlaybackOrder.DEFAULT
			org.jellyfin.playback.core.model.PlaybackOrder.RANDOM -> PlaybackOrder.SHUFFLE
			org.jellyfin.playback.core.model.PlaybackOrder.SHUFFLE -> PlaybackOrder.SHUFFLE
		}
		val isPaused = state.playState.value != PlayState.PLAYING

		runCatching {
			api.playStateApi.reportPlaybackProgress(
				PlaybackProgressInfo(
					itemId = session.item.id,
					item = reporting.item,
					playSessionId = session.playSessionId,
					mediaSourceId = session.mediaSourceId,
					liveStreamId = session.liveStreamId,
					playlistItemId = session.playlistItemId,
					canSeek = true,
					isMuted = state.volume.muted,
					volumeLevel = (state.volume.volume * 100).roundToInt(),
					isPaused = isPaused,
					aspectRatio = state.videoSize.value.aspectRatio.toString(),
					positionTicks = reporting.positionTicks,
					playMethod = playMethod,
					audioStreamIndex = audioStreamIndex,
					subtitleStreamIndex = subtitleStreamIndex,
					repeatMode = repeatMode,
					nowPlayingQueue = getQueue(),
					playbackOrder = playbackOrder,
				)
			)
		}.onFailure { error -> Timber.w("Failed to send playback update event", error) }

		@Suppress("DEPRECATION")
		runCatching {
			api.playStateApi.onPlaybackProgress(
				itemId = session.item.id,
				mediaSourceId = session.mediaSourceId,
				positionTicks = reporting.positionTicks,
				audioStreamIndex = audioStreamIndex,
				subtitleStreamIndex = subtitleStreamIndex,
				volumeLevel = (state.volume.volume * 100).roundToInt(),
				playMethod = playMethod,
				liveStreamId = session.liveStreamId,
				playSessionId = session.playSessionId,
				repeatMode = repeatMode,
				isPaused = isPaused,
				isMuted = state.volume.muted,
			)
		}.onFailure { error -> Timber.w(error, "Failed to send legacy playback update event") }

		if (session.playSessionId.isNotBlank()) {
			runCatching {
				api.playStateApi.pingPlaybackSession(session.playSessionId)
			}.onFailure { error -> Timber.w(error, "Failed to ping playback session") }
		}
	}

	private suspend fun getPlaybackReportingState(
		item: BaseItemDto,
		conversionMethod: MediaConversionMethod,
		streamStartedAt: LocalDateTime?,
		now: LocalDateTime = LocalDateTime.now(),
	): PlaybackReportingState {
		val reportingItem = getLiveTvReportingItem(item)
		val positionItem = reportingItem ?: item
		val positionTicks = withContext(Dispatchers.Main) {
			positionItem.getReportingPositionTicks(
				conversionMethod,
				state.positionInfo.active,
				streamStartedAt,
				now = now,
			)
		}

		return PlaybackReportingState(
			item = reportingItem,
			positionTicks = positionTicks,
		)
	}

	private suspend fun getLiveTvReportingItem(item: BaseItemDto): BaseItemDto? {
		val initialReportingItem = item.getLiveTvReportingItem() ?: run {
			liveTvReportingState = null
			return null
		}
		val now = LocalDateTime.now()
		val cached = liveTvReportingState?.takeIf { it.itemId == item.id }
		if (cached?.refreshAt?.isAfter(now) == true) return cached.item

		val reportingItem = if (cached != null || initialReportingItem.shouldRefreshLiveTvProgram(now)) {
			refreshLiveTvReportingItem(item) ?: initialReportingItem
		} else {
			initialReportingItem
		}

		liveTvReportingState = LiveTvReportingState(
			itemId = item.id,
			item = reportingItem,
			refreshAt = reportingItem.nextLiveTvProgramRefreshAt(now),
		)

		return reportingItem
	}

	private suspend fun refreshLiveTvReportingItem(item: BaseItemDto): BaseItemDto? {
		val channelId = item.liveTvChannelId() ?: return null

		val channel = withContext(Dispatchers.IO) {
			runCatching {
				api.liveTvApi.getChannel(channelId).content
			}.onFailure { error ->
				Timber.w(error, "Failed to refresh Live TV reporting program")
			}.getOrNull()
		} ?: return null

		return item.getLiveTvReportingItem(channel)
	}

	private fun BaseItemDto.shouldRefreshLiveTvProgram(now: LocalDateTime): Boolean {
		val program = currentProgram ?: return true
		val end = program.endDate ?: endDate ?: return true

		return !end.isAfter(now)
	}

	private fun BaseItemDto.nextLiveTvProgramRefreshAt(now: LocalDateTime): LocalDateTime {
		val program = currentProgram
		val end = program?.endDate ?: endDate

		return if (program != null && end != null && end.isAfter(now)) {
			end.plusSeconds(1)
		} else {
			now.plusNanos(LIVE_TV_PROGRAM_REFRESH_RETRY_INTERVAL.inWholeNanoseconds)
		}
	}

	private suspend fun sendStreamStop() {
		val entry = manager.queue.entry.value ?: return
		val stream = entry.mediaStream ?: return
		val item = entry.baseItem ?: return
		val reporting = getPlaybackReportingState(
			item = item,
			conversionMethod = stream.conversionMethod,
			streamStartedAt = null,
		)

		sendStreamStop(
			itemId = item.id,
			item = reporting.item,
			playSessionId = stream.identifier,
			mediaSourceId = entry.mediaSourceId,
			liveStreamId = entry.liveStreamId,
			playlistItemId = item.playlistItemId,
			positionTicks = reporting.positionTicks,
			queue = getQueue(),
		)
	}

	private suspend fun sendActiveStreamStop() {
		val session = activeSession
		if (session != null) sendStreamStop(session)
		else sendStreamStop()
	}

	private suspend fun stopActiveSessionIfChanged(entry: QueueEntry?, stream: MediaStream?) {
		val session = activeSession ?: return
		if (session.playSessionId == stream?.identifier) return

		stopProgressReports()
		if (stream != null && shouldKeepLiveStreamOpen(session, entry)) {
			Timber.d("Keeping Live TV live stream ${session.liveStreamId} open while changing playback session")
			activeSession = null
			activePlaybackKey = null
			return
		}

		sendStreamStop(session)
		activePlaybackKey = null
	}

	private fun shouldKeepLiveStreamOpen(
		session: ActivePlaybackSession,
		entry: QueueEntry?,
	): Boolean {
		val item = entry?.baseItem ?: return false
		val liveStreamId = entry.liveStreamId ?: return false

		return session.item.id == item.id &&
			!session.liveStreamId.isNullOrBlank() &&
			session.liveStreamId == liveStreamId
	}

	private suspend fun sendStreamStop(
		itemId: java.util.UUID,
		item: BaseItemDto?,
		playSessionId: String,
		mediaSourceId: String?,
		liveStreamId: String?,
		playlistItemId: String?,
		positionTicks: Long,
		queue: List<QueueItem>,
	) {
		if (stoppedPlaySessionId == playSessionId) return
		stoppedPlaySessionId = playSessionId

		reportPlaybackStopped(
			itemId = itemId,
			item = item,
			playSessionId = playSessionId,
			mediaSourceId = mediaSourceId,
			liveStreamId = liveStreamId,
			playlistItemId = playlistItemId,
			positionTicks = positionTicks,
			queue = queue,
		)
	}

	private suspend fun sendStreamStop(session: ActivePlaybackSession) {
		if (stoppedPlaySessionId == session.playSessionId) return
		stoppedPlaySessionId = session.playSessionId
		val reporting = getPlaybackReportingState(
			item = session.item,
			conversionMethod = session.conversionMethod,
			streamStartedAt = session.streamStartedAt,
		)

		reportPlaybackStopped(
			itemId = session.item.id,
			item = reporting.item,
			playSessionId = session.playSessionId,
			mediaSourceId = session.mediaSourceId,
			liveStreamId = session.liveStreamId,
			playlistItemId = session.playlistItemId,
			positionTicks = reporting.positionTicks,
			queue = session.queue,
		)
	}

	private suspend fun reportPlaybackStopped(
		itemId: java.util.UUID,
		item: BaseItemDto?,
		playSessionId: String,
		mediaSourceId: String?,
		liveStreamId: String?,
		playlistItemId: String?,
		positionTicks: Long,
		queue: List<QueueItem>,
	) {
		runCatching {
			api.playStateApi.reportPlaybackStopped(
				PlaybackStopInfo(
					itemId = itemId,
					item = item,
					playSessionId = playSessionId,
					mediaSourceId = mediaSourceId,
					liveStreamId = liveStreamId,
					playlistItemId = playlistItemId,
					positionTicks = positionTicks,
					failed = false,
					nowPlayingQueue = queue,
				)
			)
		}.onSuccess {
			Timber.i("Reported playback stopped for $itemId session $playSessionId")
			if (activeSession?.playSessionId == playSessionId) {
				activeSession = null
				activePlaybackKey = null
			}
		}.onFailure { error -> Timber.w("Failed to send playback stop event", error) }

		@Suppress("DEPRECATION")
		runCatching {
			api.playStateApi.onPlaybackStopped(
				itemId = itemId,
				mediaSourceId = mediaSourceId,
				positionTicks = positionTicks,
				liveStreamId = liveStreamId,
				playSessionId = playSessionId,
			)
		}.onSuccess {
			Timber.i("Reported legacy playback stopped for $itemId session $playSessionId")
			if (activeSession?.playSessionId == playSessionId) {
				activeSession = null
				activePlaybackKey = null
			}
		}.onFailure { error -> Timber.w(error, "Failed to send legacy playback stop event") }
	}
}
