package org.jellyfin.playback.libvlc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.backend.TrackType
import org.jellyfin.playback.core.mediastream.ExternalSubtitle
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamContainer
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.ui.PlayerSubtitleStyle
import org.videolan.libvlc.util.VLCUtil
import kotlin.time.Duration.Companion.seconds

class TimedEventTrackerTests : StringSpec({
	"only completed buffering restores playing state" {
		bufferingPlayState(99f) shouldBe null
		bufferingPlayState(100f) shouldBe PlayState.PLAYING
	}

	"LibVLC descriptions follow media track IDs with unmatched slaves appended" {
		orderedLibVlcTrackIds(
			mediaTrackIds = listOf(7, 3),
			descriptionTrackIds = listOf(-1, 3, 7, 9),
		) shouldBe listOf(7, 3, 9)
	}

	"fires an instant event once when playback crosses it" {
		var activations = 0
		val tracker = TimedEventTracker()
		tracker.setEvents(listOf(TimedEvent.Instant(position = 5.seconds) { activations++ }))

		tracker.advance(4.seconds, 6.seconds, 10.seconds, natural = true)
		tracker.advance(6.seconds, 7.seconds, 10.seconds, natural = true)

		activations shouldBe 1
	}

	"LibVLC network cache follows the normal buffer duration and live tv override" {
		libVlcMediaOptions(
			isLiveTv = true,
			normalBufferDuration = 120.seconds,
			liveTvBufferDuration = 5.seconds,
			options = LibVlcPlaybackOptions(),
		).contains(":network-caching=5000") shouldBe true
		libVlcMediaOptions(
			isLiveTv = false,
			normalBufferDuration = 120.seconds,
			liveTvBufferDuration = 5.seconds,
			options = LibVlcPlaybackOptions(),
		).contains(":network-caching=120000") shouldBe true
		libVlcMediaOptions(
			isLiveTv = false,
			normalBufferDuration = null,
			liveTvBufferDuration = 5.seconds,
			options = LibVlcPlaybackOptions(),
		).any { option -> option.startsWith(":network-caching=") } shouldBe false
	}

	"automatic deblocking follows VLC Android device defaults" {
		val slowDevice = VLCUtil.MachineSpecs().apply {
			processors = 2
			frequency = 1000f
		}
		val fastDevice = VLCUtil.MachineSpecs().apply {
			processors = 4
			frequency = 1500f
		}

		resolveDeblocking(-1, slowDevice) shouldBe 3
		resolveDeblocking(-1, fastDevice) shouldBe 1
		resolveDeblocking(4, fastDevice) shouldBe 4
	}

	"subtitle style maps to one LibVLC startup option set" {
		val options = PlayerSubtitleStyle(
			textColor = 0x80445566.toInt(),
			backgroundColor = 0x40112233,
			edgeColor = 0xC0778899.toInt(),
			textWeight = 700,
			textSizeDp = 26f,
		).libVlcOptions()

		options shouldBe listOf(
			"--freetype-rel-fontsize=26",
			"--freetype-bold",
			"--freetype-color=4478310",
			"--freetype-opacity=128",
			"--freetype-background-color=1122867",
			"--freetype-background-opacity=64",
			"--freetype-outline-thickness=4",
			"--freetype-outline-color=7833753",
			"--freetype-outline-opacity=192",
		)
	}

	"external subtitle defaults do not override an explicit off selection" {
		val subtitle = ExternalSubtitle(
			url = "http://example.test/sub.ass",
			mimeType = "text/x-ssa",
			language = "en",
			title = "English",
			index = 4,
			isDefault = true,
		)

		shouldSelectExternalSubtitle(subtitle, null) shouldBe true
		shouldSelectExternalSubtitle(subtitle, -1) shouldBe false
		shouldSelectExternalSubtitle(subtitle, 4) shouldBe true
	}

	"source track indexes follow LibVLC embedded then external subtitle order" {
		val stream = PlayableMediaStream(
			identifier = "test",
			conversionMethod = MediaConversionMethod.None,
			container = MediaStreamContainer("mkv"),
			tracks = listOf(
				MediaStreamAudioTrack(
					index = 1,
					codec = "aac",
					bitrate = 128_000,
					channels = 2,
					sampleRate = 48_000,
					language = "en",
					title = "English",
				),
				MediaStreamAudioTrack(
					index = 7,
					codec = "ac3",
					bitrate = 640_000,
					channels = 6,
					sampleRate = 48_000,
					language = "fr",
					title = "French",
				),
				MediaStreamSubtitleTrack(
					index = 4,
					codec = "srt",
					language = "fr",
					title = "French external",
					isExternal = true,
				),
				MediaStreamSubtitleTrack(
					index = 12,
					codec = "ass",
					language = "en",
					title = "Signs",
					isExternal = false,
				),
				MediaStreamSubtitleTrack(
					index = 9,
					codec = "srt",
					language = "de",
					title = "German external",
					isExternal = true,
				),
			),
			queueEntry = QueueEntry(),
			url = "http://example.test/video.mkv",
			externalSubtitles = listOf(
				ExternalSubtitle(
					url = "http://example.test/de.srt",
					mimeType = "application/x-subrip",
					language = "de",
					title = "German external",
					index = 9,
				),
				ExternalSubtitle(
					url = "http://example.test/fr.srt",
					mimeType = "application/x-subrip",
					language = "fr",
					title = "French external",
					index = 4,
				),
			),
		)

		stream.sourceTrackIndex(TrackType.AUDIO, 7) shouldBe 1
		stream.sourceTrackIndex(TrackType.SUBTITLE, 12) shouldBe 0
		stream.sourceTrackIndex(TrackType.SUBTITLE, 9) shouldBe 1
		stream.sourceTrackIndex(TrackType.SUBTITLE, 4) shouldBe 2
	}
})
