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
	test("an unmapped subtitle does not replace a saved choice with off") {
		val item = episode()
		val queue = VideoQueueManager()
		val tracks = source(stream(2, "eng"))
		TrackSelectionResolver.storeSelectedSubtitleTrack(item, tracks, queue, 2)
		TrackSelectionResolver.storeSelectedSubtitleTrack(item, tracks, queue, null)
		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(item, tracks, queue) shouldBe 2
	}

	test("replacing a queue clears transient track preferences") {
		val queue = VideoQueueManager()
		queue.setCurrentVideoQueue(listOf(item()))
		queue.setLastPlayedAudioLanguageIsoCode("jpn")
		queue.setLastPlayedSubtitleLanguageIsoCode("")
		queue.setLastPlayedSubtitleHearingImpaired(true)
		queue.setCurrentVideoQueue(listOf(item()))
		queue.getLastPlayedAudioLanguageIsoCode() shouldBe null
		queue.getLastPlayedSubtitleLanguageIsoCodes() shouldBe null
		queue.getLastPlayedSubtitleHearingImpaired() shouldBe false
	}

	test("automatic forced subtitle off is reevaluated when complete tracks arrive") {
		val item = episode()
		val queue = VideoQueueManager()
		TrackSelectionResolver.storeSelectedSubtitleTrack(item, source(stream(2, "eng").copy(isForced = true)), queue, 2)
		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(item, source(stream(3, "eng")), queue) shouldBe -1
		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(item, source(stream(8, "eng").copy(isForced = true)), queue) shouldBe 8
	}
	test("season and series choices survive clearing the playback queue") {
		val first = episode()
		val queue = VideoQueueManager()
		val selected = source(stream(2, "eng", codec = "ass").copy(isForced = true))
		TrackSelectionResolver.storeSelectedSubtitleTrack(first, selected, queue, 2)
		queue.clearVideoQueue()
		val next = source(stream(8, "eng", codec = "ass"), stream(9, "eng", codec = "ass").copy(isForced = true))
		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(first.copy(id = UUID.randomUUID()), next, queue) shouldBe 9
		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(
			first.copy(id = UUID.randomUUID(), seasonId = UUID.randomUUID()), next, queue,
		) shouldBe 9
	}

	test("saved episode matches track attributes after an index is reused") {
		val episode = episode()
		val queue = VideoQueueManager()
		TrackSelectionResolver.storeSelectedAudioTrack(episode, source(stream(2, "eng", MediaStreamType.AUDIO, "aac")), queue, 2)
		queue.clearVideoQueue()
		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(episode, source(
			stream(2, "jpn", MediaStreamType.AUDIO, "aac"),
			stream(7, "eng", MediaStreamType.AUDIO, "aac"),
		), queue) shouldBe 7
	}

	test("subtitle hearing impaired choice distinguishes tracks with the same language and codec") {
		val episode = episode()
		val queue = VideoQueueManager()
		TrackSelectionResolver.storeSelectedSubtitleTrack(
			episode, source(stream(2, "eng", codec = "srt").copy(isHearingImpaired = true)), queue, 2,
		)
		queue.clearVideoQueue()
		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(episode.copy(id = UUID.randomUUID()), source(
			stream(7, "eng", codec = "srt"),
			stream(8, "eng", codec = "srt").copy(isHearingImpaired = true),
		), queue) shouldBe 8
	}

	test("episode choice wins over later season choice and season wins over series") {
		val first = episode()
		val queue = VideoQueueManager()
		val tracks = source(stream(2, "eng", MediaStreamType.AUDIO), stream(3, "jpn", MediaStreamType.AUDIO))
		TrackSelectionResolver.storeSelectedAudioTrack(first, tracks, queue, 2)
		TrackSelectionResolver.storeSelectedAudioTrack(first.copy(id = UUID.randomUUID()), tracks, queue, 3)
		TrackSelectionResolver.storeSelectedAudioTrack(first.copy(id = UUID.randomUUID(), seasonId = UUID.randomUUID()), tracks, queue, 2)
		queue.clearVideoQueue()
		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(first, tracks, queue) shouldBe 2
		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(first.copy(id = UUID.randomUUID()), tracks, queue) shouldBe 3
		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(
			first.copy(id = UUID.randomUUID(), seasonId = UUID.randomUUID()), tracks, queue,
		) shouldBe 2
	}

	test("subtitle off persists for the season but does not affect unrelated series") {
		val first = episode()
		val queue = VideoQueueManager()
		TrackSelectionResolver.storeSelectedSubtitleTrack(first, source(), queue, -1)
		queue.clearVideoQueue()
		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(first.copy(id = UUID.randomUUID()), source(), queue) shouldBe -1
		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(episode(), source(), queue) shouldBe null
	}

	test("missing preferred tracks do not erase the remembered season choice") {
		val first = episode()
		val queue = VideoQueueManager()
		TrackSelectionResolver.storeSelectedAudioTrack(first, source(stream(2, "jpn", MediaStreamType.AUDIO, "aac")), queue, 2)
		queue.clearVideoQueue()
		val next = first.copy(id = UUID.randomUUID())
		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(next, source(stream(3, "eng", MediaStreamType.AUDIO)), queue) shouldBe null
		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(next, source(stream(7, "jpn", MediaStreamType.AUDIO, "aac")), queue) shouldBe 7
	}

	test("unmatched season choice falls back to matching series choice") {
		val first = episode()
		val queue = VideoQueueManager()
		val tracks = source(stream(2, "eng", MediaStreamType.AUDIO), stream(3, "jpn", MediaStreamType.AUDIO))
		TrackSelectionResolver.storeSelectedAudioTrack(first, tracks, queue, 3)
		TrackSelectionResolver.storeSelectedAudioTrack(first.copy(id = UUID.randomUUID(), seasonId = UUID.randomUUID()), tracks, queue, 2)
		queue.clearVideoQueue()
		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(
			first.copy(id = UUID.randomUUID()), source(stream(8, "eng", MediaStreamType.AUDIO)), queue,
		) shouldBe 8
	}

	test("forced subtitle preference does not select full dialogue when forced is missing") {
		val first = episode()
		val queue = VideoQueueManager()
		TrackSelectionResolver.storeSelectedSubtitleTrack(first, source(stream(2, "eng").copy(isForced = true)), queue, 2)
		queue.clearVideoQueue()
		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(first.copy(id = UUID.randomUUID()), source(stream(8, "eng")), queue) shouldBe -1
	}

	test("unknown language choice does not match a different known language") {
		val first = episode()
		val queue = VideoQueueManager()
		TrackSelectionResolver.storeSelectedAudioTrack(first, source(stream(2, "und", MediaStreamType.AUDIO)), queue, 2)
		queue.clearVideoQueue()
		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(
			first.copy(id = UUID.randomUUID()), source(stream(7, "eng", MediaStreamType.AUDIO)), queue,
		) shouldBe null
	}

	test("queued subtitle matching preserves hearing impaired tracks outside a series") {
		val queue = VideoQueueManager()
		TrackSelectionResolver.storeSelectedSubtitleTrack(
			item(), source(stream(2, "eng", codec = "srt").copy(isHearingImpaired = true)), queue, 2,
		)
		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(item(), source(
			stream(7, "eng", codec = "srt"),
			stream(8, "eng", codec = "srt").copy(isHearingImpaired = true),
		), queue) shouldBe 8
	}

	test("audio channel count distinguishes tracks with the same language and codec") {
		val first = episode()
		val queue = VideoQueueManager()
		TrackSelectionResolver.storeSelectedAudioTrack(first, source(stream(2, "eng", MediaStreamType.AUDIO, "aac").copy(channels = 6)), queue, 2)
		queue.clearVideoQueue()
		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(first.copy(id = UUID.randomUUID()), source(
			stream(7, "en", MediaStreamType.AUDIO, "AAC").copy(channels = 2),
			stream(8, "eng", MediaStreamType.AUDIO, "aac").copy(channels = 6),
		), queue) shouldBe 8
	}

	test("saved title distinguishes regular tracks when flags and codec are identical") {
		val first = episode()
		val queue = VideoQueueManager()
		TrackSelectionResolver.storeSelectedSubtitleTrack(first, source(stream(2, "eng", codec = "ass").copy(title = "Full dialogue")), queue, 2)
		queue.clearVideoQueue()
		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(first.copy(id = UUID.randomUUID()), source(
			stream(7, "eng", codec = "ass").copy(title = "Signs and songs"),
			stream(8, "eng", codec = "ass").copy(title = "Full dialogue"),
		), queue) shouldBe 8
	}

	test("missing SDH falls back to regular subtitles rather than forced subtitles") {
		val first = episode()
		val queue = VideoQueueManager()
		TrackSelectionResolver.storeSelectedSubtitleTrack(first, source(stream(2, "eng").copy(isHearingImpaired = true)), queue, 2)
		queue.clearVideoQueue()
		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(first.copy(id = UUID.randomUUID()), source(
			stream(7, "eng").copy(isForced = true), stream(8, "eng"),
		), queue) shouldBe 8
	}

	test("legacy episode index wins over inherited choice and gains track identity") {
		val first = episode()
		val queue = VideoQueueManager()
		val tracks = source(stream(2, "eng", MediaStreamType.AUDIO), stream(3, "jpn", MediaStreamType.AUDIO))
		TrackSelectionResolver.storeSelectedAudioTrack(first, tracks, queue, 2)
		val legacy = first.copy(id = UUID.randomUUID())
		TrackSelectionManager.setSelectedAudioTracks(listOf(legacy.id), 3)
		queue.clearVideoQueue()
		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(legacy, tracks, queue) shouldBe 3
		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(legacy, source(
			stream(3, "eng", MediaStreamType.AUDIO), stream(9, "jpn", MediaStreamType.AUDIO),
		), queue) shouldBe 9
	}

	test("invalid user selection does not overwrite a season preference") {
		val first = episode()
		val queue = VideoQueueManager()
		val tracks = source(stream(2, "eng", MediaStreamType.AUDIO))
		TrackSelectionResolver.storeSelectedAudioTrack(first, tracks, queue, 2)
		TrackSelectionResolver.storeSelectedAudioTrack(first.copy(id = UUID.randomUUID()), tracks, queue, 99) shouldBe null
		queue.clearVideoQueue()
		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(first.copy(id = UUID.randomUUID()), tracks, queue) shouldBe 2
	}

	test("original audio flag distinguishes otherwise identical tracks") {
		val first = episode()
		val queue = VideoQueueManager()
		TrackSelectionResolver.storeSelectedAudioTrack(first, source(stream(2, "eng", MediaStreamType.AUDIO).copy(isOriginal = false)), queue, 2)
		queue.clearVideoQueue()
		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(first.copy(id = UUID.randomUUID()), source(
			stream(7, "eng", MediaStreamType.AUDIO), stream(8, "eng", MediaStreamType.AUDIO).copy(isOriginal = false),
		), queue) shouldBe 8
	}

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

	test("Live TV selections wait for tracks before validation") {
		val videoQueueManager = VideoQueueManager()
		val item = liveTvItem()
		val selectedSource = source(
			stream(index = 2, language = "jpn", type = MediaStreamType.AUDIO, codec = "aac"),
			stream(index = 4, language = "spa", type = MediaStreamType.SUBTITLE, codec = "ass"),
		)
		TrackSelectionResolver.storeSelectedAudioTrack(item, selectedSource, videoQueueManager, 2)
		TrackSelectionResolver.storeSelectedSubtitleTrack(item, selectedSource, videoQueueManager, 4)

		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(item, source(), videoQueueManager) shouldBe 2
		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(item, source(), videoQueueManager) shouldBe 4
	}

	test("stale Live TV audio selection falls back to its saved language") {
		val videoQueueManager = VideoQueueManager()
		val item = liveTvItem()
		TrackSelectionResolver.storeSelectedAudioTrack(
			item,
			source(stream(index = 8, language = "jpn", type = MediaStreamType.AUDIO, codec = "aac")),
			videoQueueManager,
			8,
		)

		TrackSelectionResolver.resolvePlaybackAudioStreamIndex(
			item,
			source(stream(index = 2, language = "jpn", type = MediaStreamType.AUDIO, codec = "aac")),
			videoQueueManager,
		) shouldBe 2
	}

	test("stale Live TV subtitle selection falls back to its saved language") {
		val videoQueueManager = VideoQueueManager()
		val item = liveTvItem()
		TrackSelectionResolver.storeSelectedSubtitleTrack(
			item,
			source(stream(index = 8, language = "spa", type = MediaStreamType.SUBTITLE, codec = "ass")),
			videoQueueManager,
			8,
		)

		TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(
			item,
			source(stream(index = 4, language = "spa", type = MediaStreamType.SUBTITLE, codec = "ass")),
			videoQueueManager,
		) shouldBe 4
	}
})

private fun item() = BaseItemDto(
	id = UUID.randomUUID(),
	type = BaseItemKind.MOVIE,
)

private fun episode() = BaseItemDto(
	id = UUID.randomUUID(),
	type = BaseItemKind.EPISODE,
	seasonId = UUID.randomUUID(),
	seriesId = UUID.randomUUID(),
)

private fun liveTvItem() = BaseItemDto(
	id = UUID.randomUUID(),
	type = BaseItemKind.TV_CHANNEL,
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
