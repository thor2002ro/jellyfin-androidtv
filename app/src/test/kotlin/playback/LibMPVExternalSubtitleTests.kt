package org.jellyfin.androidtv.playback

import `is`.xyz.mpv.MPVNode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.playback.core.backend.TrackType
import org.jellyfin.playback.core.mediastream.ExternalSubtitle
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamContainer
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.mpv.LibMPVBackend

class LibMPVExternalSubtitleTests : FunSpec({
	val requested = ExternalSubtitle("https://server/selected.srt", "application/x-subrip", "ron", "Romanian", 0, true, true)
	val other = ExternalSubtitle("https://server/other.srt", "application/x-subrip", "eng", "English", 4)
	val stream = PlayableMediaStream(
		"test", MediaConversionMethod.Transcode, MediaStreamContainer("m3u8"), emptyList(), QueueEntry(),
		"https://server/video.m3u8", listOf(requested, other), selectedSubtitleStreamIndex = 0,
	)

	test("external subtitles load asynchronously without selecting a late result on their own") {
		val backend = subtitleBackend(stream)
		val commands = mutableListOf<List<String>>()
		every { backend["runCommand"](anyVararg<String>()) } answers {
			commands += firstArg<Array<String>>().toList()
			true
		}
		backend.callPrivate("addExternalSubtitles")
		backend.callPrivate("addExternalSubtitles")

		commands shouldBe listOf(
			listOf("async", "sub-add", requested.url, "auto+forced+default", "Romanian", "ron"),
			listOf("async", "sub-add", other.url, "auto", "English", "eng"),
		)
		backend.getField("pendingInitialTrackTypes") shouldBe setOf(TrackType.SUBTITLE)
	}

	test("an out-of-order external track cannot consume the pending selected subtitle even with a matching ff-index") {
		val sourceTracks = listOf(
			MediaStreamSubtitleTrack(0, "subrip", "ron", "Romanian", true),
			MediaStreamSubtitleTrack(4, "subrip", "eng", "English", true),
		)
		val sourceStream = stream.copy(tracks = sourceTracks)
		val backend = subtitleBackend(sourceStream)
		val selections = mutableListOf<Pair<String, String>>()
		every { backend["setProperty"](any<String>(), any<String>()) } answers {
			selections += firstArg<String>() to secondArg<String>()
		}
		backend.setField("pendingInitialTrackTypes", mutableSetOf(TrackType.SUBTITLE))
		backend.setTracks(externalTrack(7, other.url))
		val earlyTrack = (backend.getField("tracks") as List<*>).first()!!
		every { backend["sourceTrackFor"](sourceStream, earlyTrack, 0, sourceTracks) } answers { callOriginal() }
		backend.callPrivate("sourceTrackFor", sourceStream, earlyTrack, 0, sourceTracks) shouldBe sourceTracks[1]
		backend.callPrivate("applyInitialTrackSelection")
		selections shouldBe emptyList()
		backend.getField("pendingInitialTrackTypes") shouldBe setOf(TrackType.SUBTITLE)

		backend.setTracks(externalTrack(7, other.url), externalTrack(9, requested.url))
		backend.callPrivate("applyInitialTrackSelection")
		selections shouldBe listOf("sid" to "9")
		backend.getField("pendingInitialTrackTypes") shouldBe emptySet<TrackType>()
	}

	test("a default subtitle loads later without overriding a newer subtitle-off choice") {
		val backend = subtitleBackend(stream.copy(conversionMethod = MediaConversionMethod.None, selectedSubtitleStreamIndex = null))
		val selections = mutableListOf<Pair<String, String>>()
		every { backend["setProperty"](any<String>(), any<String>()) } answers {
			selections += firstArg<String>() to secondArg<String>()
		}
		backend.callPrivate("addExternalSubtitles")
		backend.setTracks(externalTrack(9, requested.url))
		backend.callPrivate("applyInitialTrackSelection")
		selections shouldBe listOf("sid" to "9")

		backend.setField("pendingInitialTrackTypes", mutableSetOf(TrackType.SUBTITLE))
		every { backend.selectTrack(any(), any()) } answers { callOriginal() }
		backend.selectTrack(TrackType.SUBTITLE, -1) shouldBe true
		backend.callPrivate("applyInitialTrackSelection")
		selections shouldBe listOf("sid" to "9", "sid" to "no")
	}
})

private fun subtitleBackend(stream: PlayableMediaStream): LibMPVBackend = mockk<LibMPVBackend>(relaxed = true).apply {
	setField("currentStream", stream)
	setField("externalSubtitlesAdded", false)
	setField("tracks", emptyList<Any>())
	setField("pendingInitialTrackTypes", mutableSetOf<TrackType>())
	every { this@apply["addExternalSubtitles"]() } answers { callOriginal() }
	every { this@apply["applyInitialTrackSelection"]() } answers { callOriginal() }
	every { this@apply["applyInitialTrackSelection"](any<PlayableMediaStream>(), any<TrackType>(), any<Int>()) } answers { callOriginal() }
	every { this@apply["findTrack"](any<PlayableMediaStream>(), any<TrackType>(), any<Int>()) } answers { callOriginal() }
	every { this@apply["parseTracks"](any<MPVNode>()) } answers { callOriginal() }
	every { this@apply["refreshTracks"]() } returns Unit
	every { this@apply["runCommand"](anyVararg<String>()) } returns true
}

private fun externalTrack(id: Long, url: String) = MPVNode.MapNode(mapOf(
	"id" to MPVNode.IntNode(id),
	"type" to MPVNode.StringNode("sub"),
	"ff-index" to MPVNode.IntNode(0),
	"external" to MPVNode.BooleanNode(true),
	"external-filename" to MPVNode.StringNode(url),
))

private fun LibMPVBackend.setTracks(vararg tracks: MPVNode) {
	setField("tracks", callPrivate("parseTracks", MPVNode.ArrayNode(arrayOf(*tracks))))
}

private fun LibMPVBackend.callPrivate(name: String, vararg arguments: Any): Any? =
	LibMPVBackend::class.java.declaredMethods.single { it.name == name && it.parameterCount == arguments.size }
		.apply { isAccessible = true }.invoke(this, *arguments)

private fun LibMPVBackend.setField(name: String, value: Any?) {
	LibMPVBackend::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

private fun LibMPVBackend.getField(name: String): Any? =
	LibMPVBackend::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)
