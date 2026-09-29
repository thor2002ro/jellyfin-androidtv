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
) = MediaStream(
	language = language,
	isInterlaced = false,
	isDefault = false,
	isForced = false,
	isHearingImpaired = false,
	type = MediaStreamType.SUBTITLE,
	index = index,
	isExternal = false,
	isTextSubtitleStream = true,
	supportsExternalStream = false,
)
