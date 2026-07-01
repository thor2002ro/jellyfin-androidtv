package org.jellyfin.playback.jellyfin.mediastream

import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamResolver
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.liveStreamTargetOffset
import org.jellyfin.playback.jellyfin.livetv.LiveTvPlaybackPolicy
import org.jellyfin.playback.jellyfin.JellyfinMediaStreamOptionsProvider
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.forceTranscoding
import org.jellyfin.playback.jellyfin.queue.forceTranscodingRecoveryAttempts
import org.jellyfin.playback.jellyfin.queue.forceTranscodingSourceBitrate
import org.jellyfin.playback.jellyfin.queue.liveStreamId
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.extensions.inWholeTicks
import timber.log.Timber
import kotlin.time.Duration

class JellyfinMediaStreamResolver(
	private val api: ApiClient,
	private val deviceProfileBuilder: () -> DeviceProfile,
	private val mediaStreamOptionsProvider: JellyfinMediaStreamOptionsProvider = { _, _ ->
		JellyfinMediaStreamOptions()
	},
	private val liveTvPlaybackPolicy: LiveTvPlaybackPolicy = LiveTvPlaybackPolicy(),
) : MediaStreamResolver {
	companion object {
		private val supportedMediaTypes = arrayOf(MediaType.VIDEO, MediaType.AUDIO)
		private const val LIVE_TV_UNKNOWN_SOURCE_BITRATE = 40_000_000
	}

	override suspend fun getStream(
		queueEntry: QueueEntry,
		startPosition: Duration?,
	): PlayableMediaStream? {
		val baseItem = queueEntry.baseItem
		if (baseItem == null) return null

		val isLiveTv = liveTvPlaybackPolicy.isLiveTv(baseItem)
		val mediaType = when {
			isLiveTv -> MediaType.VIDEO
			else -> baseItem.mediaType
		}
		if (!supportedMediaTypes.contains(mediaType)) return null

		val preferDirectPlay = isLiveTv && queueEntry.forceTranscoding == false
		if (preferDirectPlay) {
			liveTvPlaybackPolicy.reset(baseItem, reason = "quality reload")
			queueEntry.liveStreamTargetOffset = LiveTvPlaybackPolicy.INITIAL_LIVE_STREAM_TARGET_OFFSET
			queueEntry.forceTranscoding = null
		}

		val playbackOptions = liveTvPlaybackPolicy.getPlaybackOptions(baseItem)
		var forceTranscoding = queueEntry.forceTranscoding == true
		val mediaStreamOptions = mediaStreamOptionsProvider(baseItem, queueEntry.mediaSourceId)
		val playbackInfoMediaSourceId = queueEntry.mediaSourceId.takeUnless { isLiveTv }
		val profile = deviceProfileBuilder()
		var mediaInfo = getPlaybackInfo(
			item = baseItem,
			mediaSourceId = playbackInfoMediaSourceId,
			startPosition = startPosition,
			playbackOptions = playbackOptions,
			forceTranscoding = forceTranscoding,
			mediaStreamOptions = mediaStreamOptions,
			profile = profile,
		)

		val liveTvSourceBitrate = mediaInfo.mediaSource.liveTvSourceBitrate()
		if (
			isLiveTv &&
			!preferDirectPlay &&
			!forceTranscoding &&
			shouldForceLiveTvTranscoding(profile.maxStreamingBitrate, liveTvSourceBitrate)
		) {
			queueEntry.forceTranscoding = true
			queueEntry.forceTranscodingRecoveryAttempts = null
			queueEntry.forceTranscodingSourceBitrate = liveTvSourceBitrate
			forceTranscoding = true
			Timber.i("Forcing Live TV transcoding because source bitrate %s exceeds configured bitrate %s", liveTvSourceBitrate, profile.maxStreamingBitrate)
			mediaInfo = getPlaybackInfo(
				item = baseItem,
				mediaSourceId = playbackInfoMediaSourceId,
				startPosition = startPosition,
				playbackOptions = playbackOptions,
				forceTranscoding = true,
				mediaStreamOptions = mediaStreamOptions,
				profile = profile,
			)
		}

		val stream = when {
			// Direct play video
			!forceTranscoding && playbackOptions.enableDirectPlay && mediaInfo.mediaSource.supportsDirectPlay && mediaType == MediaType.VIDEO -> mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.None,
				url = mediaInfo.getDirectPlayVideoUrl(baseItem),
				mediaStreamOptions = mediaStreamOptions,
			)

			// Direct play audio
			!forceTranscoding && playbackOptions.enableDirectPlay && mediaInfo.mediaSource.supportsDirectPlay && mediaType == MediaType.AUDIO -> mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.None,
				mediaStreamOptions = mediaStreamOptions,
				url = api.audioApi.getAudioStreamUrl(
					itemId = baseItem.id,
					container = mediaInfo.mediaSource.container,
					mediaSourceId = mediaInfo.mediaSource.id,
					static = true,
					tag = mediaInfo.mediaSource.eTag,
					liveStreamId = mediaInfo.mediaSource.liveStreamId,
				)
			)

			// Remux (direct stream)
			!forceTranscoding && playbackOptions.enableDirectStream && mediaInfo.mediaSource.supportsDirectStream && mediaInfo.mediaSource.transcodingUrl != null -> mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.Remux,
				url = api.createUrl(requireNotNull(mediaInfo.mediaSource.transcodingUrl), ignorePathParameters = true),
				mediaStreamOptions = mediaStreamOptions,
			)

			// Transcode
			mediaInfo.mediaSource.supportsTranscoding && mediaInfo.mediaSource.transcodingUrl != null -> mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.Transcode,
				url = api.createUrl(requireNotNull(mediaInfo.mediaSource.transcodingUrl), ignorePathParameters = true),
				mediaStreamOptions = mediaStreamOptions,
			)

			// No compatible stream found
			else -> null
		}

		if (stream != null) {
			queueEntry.mediaSourceId = mediaInfo.mediaSource.id
			queueEntry.liveStreamId = mediaInfo.mediaSource.liveStreamId
		}

		return stream
	}

	private suspend fun getPlaybackInfo(
		item: BaseItemDto,
		mediaSourceId: String? = null,
		startPosition: Duration? = null,
		playbackOptions: LiveTvPlaybackPolicy.PlaybackOptions,
		forceTranscoding: Boolean,
		mediaStreamOptions: JellyfinMediaStreamOptions,
		profile: DeviceProfile = deviceProfileBuilder(),
	): MediaInfo {
		val response by api.mediaInfoApi.getPostedPlaybackInfo(
			itemId = item.id,
			data = PlaybackInfoDto(
				mediaSourceId = mediaSourceId,
				startTimeTicks = startPosition?.inWholeTicks,
				deviceProfile = profile,
				maxStreamingBitrate = profile.maxStreamingBitrate,
				enableDirectPlay = playbackOptions.enableDirectPlay && !forceTranscoding,
				enableDirectStream = playbackOptions.enableDirectStream && !forceTranscoding,
				enableTranscoding = true,
				audioStreamIndex = mediaStreamOptions.audioStreamIndex?.takeIf { it >= 0 },
				subtitleStreamIndex = mediaStreamOptions.subtitleStreamIndex,
				allowVideoStreamCopy = !forceTranscoding,
				allowAudioStreamCopy = true,
				autoOpenLiveStream = playbackOptions.autoOpenLiveStream,
				alwaysBurnInSubtitleWhenTranscoding = mediaStreamOptions.alwaysBurnInSubtitleWhenTranscoding,
			)
		)

		if (response.errorCode != null) {
			error("Failed to get media info for item ${item.id} source ${mediaSourceId}: ${response.errorCode}")
		}

		val isLiveTv = liveTvPlaybackPolicy.isLiveTv(item)
		val mediaSource = response.mediaSources
			// Filter out invalid streams (like strm files)
			.filter { isLiveTv || (it.protocol == MediaProtocol.FILE && !it.isRemote) }
			// Select first media source
			.firstOrNull { mediaSourceId == null || it.id == mediaSourceId }

		requireNotNull(mediaSource) {
			"Failed to get media info for item ${item.id} source ${mediaSourceId}: media source missing in response"
		}

		return MediaInfo(
			playSessionId = response.playSessionId.orEmpty(),
			mediaSource = mediaSource
		)
	}

	private fun shouldForceLiveTvTranscoding(maxStreamingBitrate: Int?, sourceBitrate: Int?): Boolean {
		val maxBitrate = maxStreamingBitrate?.takeIf { it > 0 } ?: return false
		val source = sourceBitrate ?: LIVE_TV_UNKNOWN_SOURCE_BITRATE

		return maxBitrate < source
	}

	private fun MediaSourceInfo.liveTvSourceBitrate(): Int? {
		val streams = mediaStreams.orEmpty()
		val videoBitrate = streams
			.firstOrNull { it.type == MediaStreamType.VIDEO }
			?.bitRate
			?.takeIf { it > 0 }
		val audioBitrate = streams
			.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == defaultAudioStreamIndex }
			?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }

		return listOfNotNull(videoBitrate, audioBitrate?.bitRate?.takeIf { it > 0 })
			.takeIf { it.isNotEmpty() }
			?.sum()
			?: bitrate?.takeIf { it > 0 }
	}

	private fun MediaInfo.getDirectPlayVideoUrl(item: BaseItemDto): String = when {
		mediaSource.isRemote && mediaSource.path != null -> mediaSource.path!!
		else -> api.videosApi.getVideoStreamUrl(
			itemId = item.id,
			container = mediaSource.container,
			mediaSourceId = mediaSource.id,
			static = true,
			tag = mediaSource.eTag,
			liveStreamId = mediaSource.liveStreamId,
		)
	}

	private fun MediaInfo.toStream(
		queueEntry: QueueEntry,
		conversionMethod: MediaConversionMethod,
		url: String,
		mediaStreamOptions: JellyfinMediaStreamOptions,
	) = PlayableMediaStream(
		identifier = playSessionId,
		conversionMethod = conversionMethod,
		container = getMediaStreamContainer(),
		tracks = getTracks(),
		queueEntry = queueEntry,
		url = url,
		externalSubtitles = getExternalSubtitles(api),
		selectedAudioStreamIndex = mediaStreamOptions.audioStreamIndex ?: mediaSource.defaultAudioStreamIndex,
		selectedSubtitleStreamIndex = mediaStreamOptions.subtitleStreamIndex ?: mediaSource.defaultSubtitleStreamIndex,
	)

}
