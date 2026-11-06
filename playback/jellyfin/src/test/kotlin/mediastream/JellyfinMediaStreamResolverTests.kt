package org.jellyfin.playback.jellyfin.mediastream

import io.github.thor2002ro.libdovi.DoviTarget
import io.github.thor2002ro.libdovi.DoviTransformRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.dovi.DoviDecision
import org.jellyfin.playback.dovi.DoviDecisionReason
import org.jellyfin.playback.dovi.DoviRoute
import org.jellyfin.playback.dovi.doviDecision
import org.jellyfin.playback.jellyfin.JellyfinDeviceProfileRequest
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.operations.MediaInfoApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.VideoRangeType
import java.util.UUID

class JellyfinMediaStreamResolverTests : FunSpec({
	suspend fun resolveVideo(transcodeUrl: String, decision: DoviDecision? = null): org.jellyfin.playback.core.mediastream.PlayableMediaStream? {
		val item = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.MOVIE,
			mediaType = MediaType.VIDEO,
		)
		val mediaSource = source(
			hdrVideoStream(),
			supportsDirectPlay = false,
			supportsDirectStream = false,
			supportsTranscoding = true,
			transcodingUrl = transcodeUrl,
			transcodingSubProtocol = MediaStreamProtocol.HLS,
			protocol = MediaProtocol.FILE,
			isRemote = false,
		)
		val api = mockk<ApiClient>()
		val mediaInfoApi = mockk<MediaInfoApi>()
		every { api.getOrCreateApi(MediaInfoApi::class, any()) } returns mediaInfoApi
		every { api.createUrl(any(), any(), any(), true) } answers { "https://example.invalid${firstArg<String>()}" }
		coEvery { mediaInfoApi.getPostedPlaybackInfo(any(), any()) } returns playbackInfoResponse(mediaSource, "session")
		val resolver = JellyfinMediaStreamResolver(
			api = api,
			deviceProfileBuilder = {
				JellyfinDeviceProfileRequest(
					profile = mockk<DeviceProfile>(relaxed = true),
					requestToken = 0,
					protectsHlsVideoCopy = true,
				)
			},
		)
		val entry = QueueEntry().apply {
			baseItem = item
			doviDecision = decision
		}

		return resolver.getStream(entry, null)
	}

	test("audio and subtitle options are resolved from the awaited media source") {
		runBlocking {
			val alternateSource = source().copy(id = "alternate")
			val item = BaseItemDto(
				id = UUID.randomUUID(),
				type = BaseItemKind.TV_CHANNEL,
				mediaSources = listOf(source(), alternateSource),
			)
			val openedSource = source(
				stream(index = 2, language = "jpn", type = MediaStreamType.AUDIO),
				stream(index = 4, language = "spa", type = MediaStreamType.SUBTITLE),
			)
			val api = mockk<ApiClient>()
			val mediaInfoApi = mockk<MediaInfoApi>()
			every { api.getOrCreateApi(MediaInfoApi::class, any()) } returns mediaInfoApi
			coEvery { mediaInfoApi.getPostedPlaybackInfo(any(), any()) } returns Response(
				PlaybackInfoResponse(
					mediaSources = listOf(openedSource),
					playSessionId = "session",
				),
				200,
				emptyMap(),
			)
			val resolver = JellyfinMediaStreamResolver(
				api = api,
				deviceProfileBuilder = { JellyfinDeviceProfileRequest(mockk<DeviceProfile>(relaxed = true), 0) },
				mediaStreamOptionsProvider = { providerItem, mediaSourceId ->
					val mediaSource = providerItem.mediaSources
						?.firstOrNull { source -> mediaSourceId == null || source.id == mediaSourceId }
					JellyfinMediaStreamOptions(
						audioStreamIndex = mediaSource.findStream(MediaStreamType.AUDIO, "jpn")?.index,
						subtitleStreamIndex = mediaSource.findStream(MediaStreamType.SUBTITLE, "spa")?.index,
					)
				},
			)
			val entry = QueueEntry().apply { baseItem = item }

			val stream = resolver.getStream(entry, null)

			coVerify(exactly = 1) { mediaInfoApi.getPostedPlaybackInfo(any(), any()) }
			stream?.selectedAudioStreamIndex shouldBe 2
			stream?.selectedSubtitleStreamIndex shouldBe 4
			// Only the accepted response should update the source used by track selection.
			entry.baseItem shouldBe item
			stream?.onAccepted?.invoke()
			entry.baseItem?.mediaSources shouldBe listOf(openedSource)
			entry.baseItem?.id shouldBe item.id
		}
	}

	test("accepted VOD metadata preserves alternate versions and their order") {
		val stream = requireNotNull(resolveVideo("/Videos/item/master.m3u8?VideoCodec=hevc&AudioCodec=aac"))
		val entry = requireNotNull(stream.queueEntry)
		val cachedSource = source()
		val alternateSource = cachedSource.copy(id = "alternate")
		entry.baseItem = requireNotNull(entry.baseItem).copy(mediaSources = listOf(cachedSource, alternateSource))
		stream.onAccepted?.invoke()
		entry.baseItem?.mediaSources?.map { it.id } shouldBe listOf(cachedSource.id, alternateSource.id)
		entry.baseItem?.mediaSources?.first()?.protocol shouldBe MediaProtocol.FILE
		entry.baseItem?.mediaSources?.last() shouldBe alternateSource
	}

	test("partial Live TV tracks do not invalidate a saved selection before the response") {
		runBlocking {
			val item = BaseItemDto(
				id = UUID.randomUUID(),
				type = BaseItemKind.TV_CHANNEL,
				mediaSources = listOf(source(stream(index = 0, language = "eng", type = MediaStreamType.AUDIO))),
			)
			val openedSource = source(
				stream(index = 8, language = "jpn", type = MediaStreamType.AUDIO),
				defaultAudioStreamIndex = 0,
			)
			val requests = mutableListOf<org.jellyfin.sdk.model.api.PlaybackInfoDto>()
			val api = mockk<ApiClient>()
			val mediaInfoApi = mockk<MediaInfoApi>()
			every { api.getOrCreateApi(MediaInfoApi::class, any()) } returns mediaInfoApi
			coEvery { mediaInfoApi.getPostedPlaybackInfo(any(), capture(requests)) } returns playbackInfoResponse(openedSource, "session")
			var savedAudioStreamIndex: Int? = 8
			val resolver = JellyfinMediaStreamResolver(
				api = api,
				deviceProfileBuilder = { JellyfinDeviceProfileRequest(mockk<DeviceProfile>(relaxed = true), 0) },
				mediaStreamOptionsProvider = { providerItem, _ ->
					val audioStreams = providerItem.mediaSources
						?.firstOrNull()
						?.mediaStreams
						.orEmpty()
						.filter { it.type == MediaStreamType.AUDIO }
					val selectedIndex = savedAudioStreamIndex
					if (audioStreams.isNotEmpty() && audioStreams.none { it.index == selectedIndex }) {
						savedAudioStreamIndex = null
					}
					JellyfinMediaStreamOptions(audioStreamIndex = savedAudioStreamIndex)
				},
			)
			val entry = QueueEntry().apply { baseItem = item }

			val stream = resolver.getStream(entry, null)

			requests.single().audioStreamIndex shouldBe 8
			stream?.selectedAudioStreamIndex shouldBe 8
		}
	}

	test("server output is renegotiated on the opened source with resolved tracks") {
		runBlocking {
			val item = BaseItemDto(
				id = UUID.randomUUID(),
				type = BaseItemKind.TV_CHANNEL,
			)
			val openedSource = source(
				stream(index = 0, language = "eng", type = MediaStreamType.AUDIO),
				stream(index = 2, language = "jpn", type = MediaStreamType.AUDIO),
				stream(index = 4, language = "spa", type = MediaStreamType.SUBTITLE),
				supportsDirectPlay = false,
				supportsDirectStream = false,
				supportsTranscoding = true,
				transcodingUrl = "/videos/transcode.m3u8",
				liveStreamId = "live-stream",
				defaultAudioStreamIndex = 0,
			)
			val negotiatedSource = openedSource.copy(
				defaultAudioStreamIndex = 2,
				defaultSubtitleStreamIndex = 4,
			)
			val requests = mutableListOf<org.jellyfin.sdk.model.api.PlaybackInfoDto>()
			val api = mockk<ApiClient>()
			val mediaInfoApi = mockk<MediaInfoApi>()
			every { api.getOrCreateApi(MediaInfoApi::class, any()) } returns mediaInfoApi
			every { api.createUrl(any(), any(), any(), true) } returns "https://example.invalid/transcode.m3u8"
			coEvery { mediaInfoApi.getPostedPlaybackInfo(any(), capture(requests)) } returnsMany listOf(
				playbackInfoResponse(openedSource, "open-session"),
				playbackInfoResponse(negotiatedSource, "selected-session"),
			)
			val resolver = JellyfinMediaStreamResolver(
				api = api,
				deviceProfileBuilder = { JellyfinDeviceProfileRequest(mockk<DeviceProfile>(relaxed = true), 0) },
				mediaStreamOptionsProvider = { providerItem, _ ->
					val streams = providerItem.mediaSources?.firstOrNull()?.mediaStreams.orEmpty()
					JellyfinMediaStreamOptions(
						audioStreamIndex = streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.language == "jpn" }?.index,
						subtitleStreamIndex = streams.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.language == "spa" }?.index,
					)
				},
			)
			val entry = QueueEntry().apply { baseItem = item }

			val stream = resolver.getStream(entry, null)

			requests.size shouldBe 2
			requests[1].mediaSourceId shouldBe "live-source"
			requests[1].liveStreamId shouldBe "live-stream"
			requests[1].audioStreamIndex shouldBe 2
			requests[1].subtitleStreamIndex shouldBe 4
			stream?.identifier shouldBe "selected-session"
			stream?.selectedAudioStreamIndex shouldBe 2
			stream?.selectedSubtitleStreamIndex shouldBe 4
		}
	}

	test("HDR playback preserves video when the server converts only audio") {
		runBlocking {
			val transcodeUrl = "/Videos/item/master.m3u8?VideoCodec=hevc,h264&TranscodeReasons=AudioCodecNotSupported"
			resolveVideo(transcodeUrl)?.conversionMethod shouldBe MediaConversionMethod.Remux
		}
	}

	test("libdovi source-base conversion keeps the original video") {
		runBlocking {
			val transcodeUrl = "/Videos/item/master.m3u8?VideoCodec=hevc,h264&hevc-rangetype=SDR&TranscodeReasons=AudioCodecNotSupported"
			val decision = DoviDecision(
				route = DoviRoute.SourceBase(DoviTransformRequest(DoviTarget.SOURCE_BASE_PRESENTATION)),
				reason = DoviDecisionReason.SOURCE_BASE,
			)

			resolveVideo(transcodeUrl, decision)?.conversionMethod shouldBe MediaConversionMethod.Remux
		}
	}

	test("decoder policy does not override an audio-only HLS video copy") {
		runBlocking {
			val transcodeUrl = "/Videos/item/master.m3u8?VideoCodec=hevc,h264&TranscodeReasons=AudioCodecNotSupported"
			val decision = DoviDecision(
				route = DoviRoute.ServerFallback,
				reason = DoviDecisionReason.NO_COMPATIBLE_ROUTE,
			)

			resolveVideo(transcodeUrl, decision)?.conversionMethod shouldBe MediaConversionMethod.Remux
		}
	}

	test("resolved server stream requests a safe DTS encoding bitrate while preserving video copy") {
		runBlocking {
			val stream = resolveVideo("/Videos/item/master.m3u8?VideoCodec=hevc&AudioCodec=dts&AudioBitrate=640000&TranscodeReasons=AudioCodecNotSupported")
			stream?.url shouldBe "https://example.invalid/Videos/item/master.m3u8?VideoCodec=hevc&AudioCodec=dts&AudioBitrate=768000&TranscodeReasons=AudioCodecNotSupported"
			stream?.conversionMethod shouldBe MediaConversionMethod.Remux
		}
	}
})

private fun playbackInfoResponse(source: MediaSourceInfo, sessionId: String) = Response(
	PlaybackInfoResponse(
		mediaSources = listOf(source),
		playSessionId = sessionId,
	),
	200,
	emptyMap(),
)

private fun MediaSourceInfo?.findStream(type: MediaStreamType, language: String) =
	this?.mediaStreams.orEmpty().firstOrNull { stream -> stream.type == type && stream.language == language }

private fun source(
	vararg streams: MediaStream,
	supportsDirectPlay: Boolean = true,
	supportsDirectStream: Boolean = true,
	supportsTranscoding: Boolean = false,
	transcodingUrl: String? = null,
	transcodingSubProtocol: MediaStreamProtocol = MediaStreamProtocol.HTTP,
	liveStreamId: String? = null,
	defaultAudioStreamIndex: Int? = null,
	protocol: MediaProtocol = MediaProtocol.HTTP,
	isRemote: Boolean = true,
) = MediaSourceInfo(
	protocol = protocol,
	id = "live-source",
	path = "https://example.invalid/live.ts",
	type = MediaSourceType.DEFAULT,
	container = "ts",
	isRemote = isRemote,
	readAtNativeFramerate = false,
	ignoreDts = false,
	ignoreIndex = false,
	genPtsInput = false,
	supportsTranscoding = supportsTranscoding,
	supportsDirectStream = supportsDirectStream,
	supportsDirectPlay = supportsDirectPlay,
	isInfiniteStream = true,
	requiresOpening = false,
	requiresClosing = false,
	liveStreamId = liveStreamId,
	requiresLooping = false,
	supportsProbing = false,
	mediaStreams = streams.toList(),
	transcodingUrl = transcodingUrl,
	transcodingSubProtocol = transcodingSubProtocol,
	defaultAudioStreamIndex = defaultAudioStreamIndex,
	defaultSubtitleStreamIndex = -1,
	hasSegments = false,
)

private fun stream(index: Int, language: String, type: MediaStreamType) = MediaStream(
	language = language,
	codec = if (type == MediaStreamType.AUDIO) "aac" else "dvbsub",
	isInterlaced = false,
	isDefault = false,
	isForced = false,
	isHearingImpaired = false,
	type = type,
	index = index,
	isExternal = false,
	isOriginal = true,
	isTextSubtitleStream = false,
	supportsExternalStream = false,
)

private fun hdrVideoStream() = MediaStream(
	codec = "hevc",
	videoRangeType = VideoRangeType.HDR10,
	isInterlaced = false,
	isDefault = true,
	isForced = false,
	isHearingImpaired = false,
	type = MediaStreamType.VIDEO,
	index = 0,
	isExternal = false,
	isOriginal = true,
	isTextSubtitleStream = false,
	supportsExternalStream = false,
)
