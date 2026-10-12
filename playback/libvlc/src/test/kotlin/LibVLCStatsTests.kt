package org.jellyfin.playback.libvlc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import org.videolan.libvlc.interfaces.IMedia

class LibVLCStatsTests : StringSpec({
	"libVLC statistics expose transport video and audio diagnostics" {
		val stats = IMedia.Stats(
			1_048_576,
			131_072f,
			2_097_152,
			262_144f,
			3,
			4,
			120,
			240,
			118,
			2,
			480,
			1,
			5,
			4_096,
			512f,
		)

		libVLCBackendDetails(stats, LibVLCRates(decodedFps = 24f, displayedFps = 23.6f))
			.shouldContainExactly(mapOf(
				"Input" to "1.00 MiB read, 128.00 KiB/s",
				"Demux" to "2.00 MiB read, 256.00 KiB/s",
				"Video pictures" to "120 decoded, 118 displayed",
				"Video rate" to "24.000 decoded/s, 23.600 displayed/s",
				"Audio buffers" to "240 decoded, 480 played, 1 lost",
				"Demux discontinuities" to "4",
				"Stream output" to "5 packets, 4.00 KiB, 512 B/s",
			))
	}

	"libVLC rates use counter differences over elapsed time" {
		val previous = LibVLCStatsSample(decodedVideo = 100, displayedPictures = 90, elapsedRealtimeNanos = 1_000_000_000)
		val current = LibVLCStatsSample(decodedVideo = 148, displayedPictures = 136, elapsedRealtimeNanos = 3_000_000_000)

		val rates = calculateLibVLCRates(previous, current)!!

		rates.decodedFps.shouldBeExactly(24f)
		rates.displayedFps.shouldBeExactly(23f)
	}

	"libVLC rates reject reset counters" {
		val previous = LibVLCStatsSample(decodedVideo = 100, displayedPictures = 90, elapsedRealtimeNanos = 1_000_000_000)
		val current = LibVLCStatsSample(decodedVideo = 2, displayedPictures = 1, elapsedRealtimeNanos = 2_000_000_000)

		calculateLibVLCRates(previous, current) shouldBe null
	}

	"libVLC statistics hide inactive stream output garbage" {
		val stats = IMedia.Stats(
			0, 0f, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 12_884_901_888, 0f,
		)

		libVLCBackendDetails(stats, rates = null).shouldNotContainKey("Stream output")
	}

	"libVLC selected tracks expose codec profile level and rates" {
		val video = IMedia.VideoTrack(
			"video/0", "Main video", true, "hevc", "hvc1", 0, 2, 153, 12_000_000,
			"und", null, 1080, 1920, 1, 1, 24_000, 1_001, 0, 0,
		)
		val audio = IMedia.AudioTrack(
			"audio/0", "English", true, "eac3", "ec-3", 0, 0, 0, 640_000,
			"eng", null, 6, 48_000,
		)

		libVLCTrackDiagnostics(video, audio) shouldBe LibVLCTrackDiagnostics(
			videoCodec = "hevc",
			videoBitrate = 12_000_000,
			videoSourceFps = 24_000f / 1_001f,
			audioCodec = "eac3",
			audioBitrate = 640_000,
			audioChannels = "6",
			audioSampleRate = 48_000,
			details = mapOf(
				"Video profile" to "2",
				"Video level" to "153",
			),
		)
	}
})
