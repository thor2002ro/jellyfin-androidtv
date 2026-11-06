package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.UUID

class TrackSelectionResolverTests : FunSpec({
	test("preferred subtitle languages are tried in order") {
		val videoQueueManager = VideoQueueManager()
		videoQueueManager.setLastPlayedSubtitleLanguageIsoCodes(listOf("de", "fr", "en"))

		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(
			item = item(),
			mediaSource = source(
				stream(index = 0, language = "eng"),
				stream(index = 1, language = "fre"),
			),
			videoQueueManager = videoQueueManager,
		) shouldBe 1
	}

	test("explicit audio and subtitle selections survive resuming the same item") {
		val videoQueueManager = VideoQueueManager()
		val item = item()
		val mediaSource = source(
			stream(index = 2, language = "jpn", type = MediaStreamType.AUDIO, codec = "aac"),
			stream(index = 4, language = "spa", type = MediaStreamType.SUBTITLE, codec = "ass"),
		)

		TrackSelectionResolver.storeSelectedAudioTrack(item, mediaSource, videoQueueManager, 2)
		TrackSelectionResolver.storeSelectedSubtitleTrack(item, mediaSource, videoQueueManager, 4)

		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(item, mediaSource, videoQueueManager) shouldBe 2
		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(item, mediaSource, videoQueueManager) shouldBe 4
	}

	test("audio and subtitle preferences carry into the next episode by language") {
		val videoQueueManager = VideoQueueManager()
		val firstEpisode = item()
		val firstSource = source(
			stream(index = 2, language = "jpn", type = MediaStreamType.AUDIO, codec = "aac"),
			stream(index = 4, language = "spa", type = MediaStreamType.SUBTITLE, codec = "ass"),
		)
		TrackSelectionResolver.storeSelectedAudioTrack(firstEpisode, firstSource, videoQueueManager, 2)
		TrackSelectionResolver.storeSelectedSubtitleTrack(firstEpisode, firstSource, videoQueueManager, 4)

		val nextEpisode = item()
		val nextSource = source(
			stream(index = 7, language = "jpn", type = MediaStreamType.AUDIO, codec = "aac"),
			stream(index = 9, language = "spa", type = MediaStreamType.SUBTITLE, codec = "ass"),
		)

		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(nextEpisode, nextSource, videoQueueManager) shouldBe 7
		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(nextEpisode, nextSource, videoQueueManager) shouldBe 9
	}

	test("disabled subtitles remain disabled in the next episode") {
		val videoQueueManager = VideoQueueManager()
		TrackSelectionResolver.storeSelectedSubtitleTrack(item(), source(), videoQueueManager, -1)

		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(
			item = item(),
			mediaSource = source(stream(index = 3, language = "eng", type = MediaStreamType.SUBTITLE, codec = "srt")),
			videoQueueManager = videoQueueManager,
		) shouldBe -1
	}
})

private fun item() = BaseItemDto(
	id = UUID.randomUUID(),
	type = BaseItemKind.MOVIE,
)

private fun source(vararg streams: MediaStream) = MediaSourceInfo(
	protocol = MediaProtocol.FILE,
	type = MediaSourceType.DEFAULT,
	isRemote = false,
	readAtNativeFramerate = false,
	ignoreDts = false,
	ignoreIndex = false,
	genPtsInput = false,
	supportsTranscoding = false,
	supportsDirectStream = true,
	supportsDirectPlay = true,
	isInfiniteStream = false,
	requiresOpening = false,
	requiresClosing = false,
	requiresLooping = false,
	supportsProbing = false,
	mediaStreams = streams.toList(),
	transcodingSubProtocol = MediaStreamProtocol.HTTP,
	defaultSubtitleStreamIndex = -1,
	hasSegments = false,
)

private fun stream(
	index: Int,
	language: String,
	type: MediaStreamType = MediaStreamType.SUBTITLE,
	codec: String? = null,
) = MediaStream(
	language = language,
	codec = codec,
	isInterlaced = false,
	isDefault = false,
	isForced = false,
	isHearingImpaired = false,
	type = type,
	index = index,
	isExternal = false,
	isOriginal = true,
	isTextSubtitleStream = type == MediaStreamType.SUBTITLE,
	supportsExternalStream = false,
)
