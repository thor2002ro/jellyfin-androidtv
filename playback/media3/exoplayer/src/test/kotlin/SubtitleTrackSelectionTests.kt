package org.jellyfin.playback.media3.exoplayer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamContainer
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.queue.QueueEntry

class SubtitleTrackSelectionTests : FunSpec({
	listOf(MediaConversionMethod.Remux, MediaConversionMethod.Transcode).forEach { conversionMethod ->
		test("$conversionMethod keeps external subtitle selection in Media3") {
			val subtitle = subtitle(index = 4, isExternal = true)
			val stream = stream(
				conversionMethod = conversionMethod,
				subtitle = subtitle,
				selectedSubtitleStreamIndex = 4,
			)

			stream.initialLocalSubtitleStreamIndex() shouldBe 4
		}

		test("$conversionMethod can start with subtitles disabled") {
			stream(
				conversionMethod = conversionMethod,
				subtitle = subtitle(index = 4, isExternal = true),
				selectedSubtitleStreamIndex = -1,
			).initialLocalSubtitleStreamIndex() shouldBe -1
		}
	}

	test("server-owned subtitles remain server-selected") {
		val internalSubtitle = subtitle(index = 3, isExternal = false)
		val remux = stream(
			conversionMethod = MediaConversionMethod.Remux,
			subtitle = internalSubtitle,
			selectedSubtitleStreamIndex = 3,
		)
		val transcode = stream(
			conversionMethod = MediaConversionMethod.Transcode,
			subtitle = internalSubtitle,
			selectedSubtitleStreamIndex = 3,
		)

		remux.initialLocalSubtitleStreamIndex() shouldBe null
		transcode.initialLocalSubtitleStreamIndex() shouldBe null
	}
})

private fun stream(
	conversionMethod: MediaConversionMethod,
	subtitle: MediaStreamSubtitleTrack,
	selectedSubtitleStreamIndex: Int,
) = PlayableMediaStream(
	identifier = "test",
	conversionMethod = conversionMethod,
	container = MediaStreamContainer("mp4"),
	tracks = listOf(subtitle),
	queueEntry = QueueEntry(),
	url = "https://example.invalid/video.m3u8",
	selectedAudioStreamIndex = 1,
	selectedSubtitleStreamIndex = selectedSubtitleStreamIndex,
)

private fun subtitle(index: Int, isExternal: Boolean) = MediaStreamSubtitleTrack(
	index = index,
	codec = "subrip",
	language = "ron",
	title = "Romanian",
	isExternal = isExternal,
)
