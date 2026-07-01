package org.jellyfin.androidtv.ui.composable.item

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.UUID

class ItemCardBaseItemOverlayTests : FunSpec({
	test("stream badges prioritize preferred default then rest") {
		val item = item(
			source(
				stream(MediaStreamType.AUDIO, 0, "jpn"),
				stream(MediaStreamType.AUDIO, 1, "eng", isDefault = true),
				stream(MediaStreamType.AUDIO, 2, "fre"),
				stream(MediaStreamType.AUDIO, 3, "ger"),
				defaultAudioStreamIndex = 1,
			)
		)

		item.streamBadges(
			audioLanguagePreference = "fr",
			subtitleLanguagePreference = null,
		)?.audio shouldBe "FR EN JP +1"
	}

	test("stream badges count hidden subtitles") {
		val item = item(
			source(
				stream(MediaStreamType.SUBTITLE, 0, "eng"),
				stream(MediaStreamType.SUBTITLE, 1, "jpn"),
				stream(MediaStreamType.SUBTITLE, 2, "fre"),
				stream(MediaStreamType.SUBTITLE, 3, "ger"),
				defaultSubtitleStreamIndex = -1,
			)
		)

		item.streamBadges(
			audioLanguagePreference = null,
			subtitleLanguagePreference = "de",
		)?.subtitle shouldBe "DE EN JP +1"
	}

	test("stream badges show on season cards") {
		val item = item(
			source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true)),
			type = BaseItemKind.SEASON,
		)

		item.streamBadges(
			audioLanguagePreference = null,
			subtitleLanguagePreference = null,
		)?.audio shouldBe "EN"
	}
})

private fun item(source: MediaSourceInfo, type: BaseItemKind = BaseItemKind.MOVIE) = BaseItemDto(
	id = UUID.randomUUID(),
	type = type,
	mediaSources = listOf(source),
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
) = MediaStream(
	language = language,
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
