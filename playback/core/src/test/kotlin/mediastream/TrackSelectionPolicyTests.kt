package org.jellyfin.playback.core.mediastream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.backend.TrackType
import org.jellyfin.playback.core.queue.QueueEntry

class TrackSelectionPolicyTests : FunSpec({
	test("direct play keeps track selection local") {
		val stream = stream(MediaConversionMethod.None, selectedSubtitleStreamIndex = 3)

		stream.allowsLocalTrackSelection(TrackType.AUDIO, track = null) shouldBe true
		stream.allowsLocalSubtitleDisable() shouldBe true
	}

	listOf(MediaConversionMethod.Remux, MediaConversionMethod.Transcode).forEach { conversionMethod ->
		test("$conversionMethod keeps external subtitles local when video does not contain subtitles") {
			val externalSubtitle = subtitle(index = 4, isExternal = true)
			val stream = stream(
				conversionMethod = conversionMethod,
				tracks = listOf(externalSubtitle),
				selectedSubtitleStreamIndex = 4,
			)

			stream.allowsLocalTrackSelection(TrackType.SUBTITLE, externalSubtitle) shouldBe true
			stream.allowsLocalSubtitleDisable() shouldBe true
			stream.allowsLocalTrackSelection(TrackType.AUDIO, track = null) shouldBe false
		}
	}

	test("video transcode keeps server-rendered subtitles server-owned") {
		val internalSubtitle = subtitle(index = 3, isExternal = false)
		val externalSubtitle = subtitle(index = 4, isExternal = true)
		val stream = stream(
			conversionMethod = MediaConversionMethod.Transcode,
			tracks = listOf(internalSubtitle, externalSubtitle),
			selectedSubtitleStreamIndex = 3,
		)

		stream.allowsLocalTrackSelection(TrackType.SUBTITLE, externalSubtitle) shouldBe false
		stream.allowsLocalSubtitleDisable() shouldBe false
	}

	test("remux can replace a server-selected subtitle with an external subtitle") {
		val internalSubtitle = subtitle(index = 3, isExternal = false)
		val externalSubtitle = subtitle(index = 4, isExternal = true)
		val stream = stream(
			conversionMethod = MediaConversionMethod.Remux,
			tracks = listOf(internalSubtitle, externalSubtitle),
			selectedSubtitleStreamIndex = 3,
		)

		stream.allowsLocalTrackSelection(TrackType.SUBTITLE, internalSubtitle) shouldBe false
		stream.allowsLocalTrackSelection(TrackType.SUBTITLE, externalSubtitle) shouldBe true
		stream.allowsLocalSubtitleDisable() shouldBe true
	}
})

private fun stream(
	conversionMethod: MediaConversionMethod,
	tracks: List<MediaStreamTrack> = emptyList(),
	selectedSubtitleStreamIndex: Int? = null,
) = PlayableMediaStream(
	identifier = "test",
	conversionMethod = conversionMethod,
	container = MediaStreamContainer("mp4"),
	tracks = tracks,
	queueEntry = QueueEntry(),
	url = "https://example.invalid/video.m3u8",
	selectedSubtitleStreamIndex = selectedSubtitleStreamIndex,
)

private fun subtitle(index: Int, isExternal: Boolean) = MediaStreamSubtitleTrack(
	index = index,
	codec = "subrip",
	language = "ron",
	title = "Romanian",
	isExternal = isExternal,
)
