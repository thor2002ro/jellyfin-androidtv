package org.jellyfin.androidtv.ui.composable.item

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.util.sdk.videoResolutionName
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID
import kotlin.time.Duration.Companion.hours

class ItemCardBaseItemOverlayTests : FunSpec({
	test("language badges prioritize preferred default then rest") {
		val item = item(
			source(
				stream(MediaStreamType.AUDIO, 0, "jpn"),
				stream(MediaStreamType.AUDIO, 1, "eng", isDefault = true),
				stream(MediaStreamType.AUDIO, 2, "fre"),
				stream(MediaStreamType.AUDIO, 3, "ger"),
				defaultAudioStreamIndex = 1,
			)
		)

		item.languageBadges(
			audioLanguagePreference = "fr",
			subtitleLanguagePreference = null,
		)?.audio shouldBe "FR EN JP +1"
	}

	test("language badges count hidden subtitles") {
		val item = item(
			source(
				stream(MediaStreamType.SUBTITLE, 0, "eng"),
				stream(MediaStreamType.SUBTITLE, 1, "jpn"),
				stream(MediaStreamType.SUBTITLE, 2, "fre"),
				stream(MediaStreamType.SUBTITLE, 3, "ger"),
				defaultSubtitleStreamIndex = -1,
			)
		)

		item.languageBadges(
			audioLanguagePreference = null,
			subtitleLanguagePreference = "de",
		)?.subtitle shouldBe "DE EN JP +1"
	}

	test("language badges show on season cards") {
		val item = item(
			source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true)),
			type = BaseItemKind.SEASON,
		)

		item.languageBadges(
			audioLanguagePreference = null,
			subtitleLanguagePreference = null,
		)?.audio shouldBe "EN"
	}

	test("language badges skip video-only media sources") {
		val item = item(
			source(stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080)),
			source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true)),
		)

		item.languageBadges(
			audioLanguagePreference = null,
			subtitleLanguagePreference = null,
		)?.audio shouldBe "EN"
	}

	test("language badges resolve audio and subtitles from separate media sources") {
		val item = item(
			source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true)),
			source(stream(MediaStreamType.SUBTITLE, 1, "spa")),
		)

		val badges = item.languageBadges(
			audioLanguagePreference = null,
			subtitleLanguagePreference = null,
		)

		badges?.audio shouldBe "EN"
		badges?.subtitle shouldBe "ES"
	}

	test("language badges aggregate multiple media source languages") {
		val item = item(
			source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true)),
			source(stream(MediaStreamType.AUDIO, 1, "jpn", isDefault = true)),
		)

		item.languageBadges(
			audioLanguagePreference = null,
			subtitleLanguagePreference = null,
		)?.audio shouldBe "EN JP"
	}

	test("language badges map unknown language to undetermined") {
		val item = item(
			source(
				stream(MediaStreamType.AUDIO, 0, "unknown", isDefault = true),
				stream(MediaStreamType.SUBTITLE, 1, "unknown"),
			),
		)
		val badges = item.languageBadges(
			audioLanguagePreference = null,
			subtitleLanguagePreference = null,
		)

		badges?.audio shouldBe "UND"
		badges?.subtitle shouldBe "UND"
	}

	test("video badges show resolution and codec") {
		val item = item(
			source(stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080)),
		)

		item.videoBadges() shouldBe VideoBadges(
			resolution = "1080",
			codec = "H264",
		)
	}

	test("language and video badges show on direct video item types") {
		listOf(
			BaseItemKind.EPISODE,
			BaseItemKind.MOVIE,
			BaseItemKind.MUSIC_VIDEO,
			BaseItemKind.TRAILER,
			BaseItemKind.VIDEO,
		).forEach { type ->
			val item = item(
				source(
					stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080),
					stream(MediaStreamType.AUDIO, 1, "eng", isDefault = true),
				),
				type = type,
			)

			item.languageBadges(
				audioLanguagePreference = null,
				subtitleLanguagePreference = null,
			)?.audio shouldBe "EN"
			item.videoBadges() shouldBe VideoBadges(
				resolution = "1080",
				codec = "H264",
			)
		}
	}

	test("video badges use resolution buckets without SD") {
		val item = item(
			source(
				stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 640, height = 360),
				stream(MediaStreamType.VIDEO, 1, "und", codec = "hevc", width = 1024, height = 576),
				stream(MediaStreamType.VIDEO, 2, "und", codec = "av1", width = 2560, height = 1440),
				stream(MediaStreamType.VIDEO, 3, "und", codec = "mpeg2", width = 320, height = 180),
			),
		)

		item.videoBadges()?.resolution shouldBe "480/1440/180"
		videoResolutionName(320, 180) shouldBe "180p"
	}

	test("series video badges show up to three resolutions and codecs") {
		val item = item(
			source(
				stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080),
				stream(MediaStreamType.VIDEO, 1, "und", codec = "hevc", width = 3840, height = 2160),
				stream(MediaStreamType.VIDEO, 2, "und", codec = "av1", width = 1280, height = 720),
				stream(MediaStreamType.VIDEO, 3, "und", codec = "vp9", width = 720, height = 480),
			),
			type = BaseItemKind.SERIES,
		)

		item.videoBadges() shouldBe VideoBadges(
			resolution = "1080/4k/720",
			codec = "H264/HEVC/AV1",
		)
	}

	test("video badges normalize duplicate and invalid metadata") {
		val item = item(
			source(
				stream(MediaStreamType.VIDEO, 0, "und", codec = " h264 ", width = 1920, height = 1080),
				stream(MediaStreamType.VIDEO, 1, "und", codec = "H264", width = 1920, height = 1080),
				stream(MediaStreamType.VIDEO, 2, "und", codec = " ", width = 0, height = 1080),
			),
		)

		item.videoBadges() shouldBe VideoBadges(
			resolution = "1080",
			codec = "H264",
		)
	}

	test("video badges are hidden without video metadata") {
		val item = item(
			source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true)),
			type = BaseItemKind.AUDIO,
		)

		item.videoBadges() shouldBe null
	}

	test("stored progress is formatted from played percentage") {
		val item = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.MOVIE,
			runTimeTicks = 72_000_000_000L,
			userData = userData(playedPercentage = 50.0),
		)

		item.storedPlayedProgress() shouldBe 0.5f
		item.remainingPlaybackTimeText(item.storedPlayedProgress()) shouldBe "-1:00:00"
	}

	test("stored progress is hidden for invalid percentages") {
		listOf(null, -1.0, 0.0, 100.0, 101.0).forEach { playedPercentage ->
			val item = BaseItemDto(
				id = UUID.randomUUID(),
				type = BaseItemKind.MOVIE,
				runTimeTicks = 72_000_000_000L,
				userData = userData(playedPercentage = playedPercentage),
			)

			item.storedPlayedProgress() shouldBe null
			item.remainingPlaybackTimeText(item.storedPlayedProgress()) shouldBe null
		}
	}

	test("remaining playback time is hidden for invalid progress") {
		val item = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.MOVIE,
			runTimeTicks = 72_000_000_000L,
		)

		listOf(Float.NaN, -0.1f, 1.1f).forEach { progress ->
			item.remainingPlaybackTimeText(progress) shouldBe null
		}
	}

	test("remaining playback time is hidden without runtime") {
		val item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, userData = userData(playedPercentage = 50.0))

		item.remainingPlaybackTimeText(item.storedPlayedProgress()) shouldBe null
	}

	test("remaining playback time can use active playback duration") {
		val item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE)

		item.remainingPlaybackTimeText(progress = 0.5f, duration = 2.hours) shouldBe "-1:00:00"
	}
})

private fun item(vararg sources: MediaSourceInfo, type: BaseItemKind = BaseItemKind.MOVIE) = BaseItemDto(
	id = UUID.randomUUID(),
	type = type,
	mediaSources = sources.toList(),
)

private fun source(
	vararg streams: MediaStream,
	defaultAudioStreamIndex: Int? = null,
	defaultSubtitleStreamIndex: Int? = null,
) = MediaSourceInfo(
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
	defaultAudioStreamIndex = defaultAudioStreamIndex,
	defaultSubtitleStreamIndex = defaultSubtitleStreamIndex,
	hasSegments = false,
)

private fun stream(
	type: MediaStreamType,
	index: Int,
	language: String,
	isDefault: Boolean = false,
	codec: String? = null,
	width: Int? = null,
	height: Int? = null,
) = MediaStream(
	language = language,
	codec = codec,
	width = width,
	height = height,
	isInterlaced = false,
	isDefault = isDefault,
	isForced = false,
	isHearingImpaired = false,
	type = type,
	index = index,
	isExternal = false,
	isTextSubtitleStream = type == MediaStreamType.SUBTITLE,
	supportsExternalStream = false,
)

private fun userData(playedPercentage: Double?) = UserItemDataDto(
	played = false,
	playedPercentage = playedPercentage,
	playbackPositionTicks = 0,
	unplayedItemCount = null,
	isFavorite = false,
	likes = null,
	lastPlayedDate = null,
	playCount = 0,
	rating = null,
	key = "",
	itemId = UUID.randomUUID(),
)
