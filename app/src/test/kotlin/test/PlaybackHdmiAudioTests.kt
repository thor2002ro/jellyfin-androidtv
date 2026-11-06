package test

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.test.PlaybackAudioDescriptor
import org.jellyfin.androidtv.test.PlaybackHdmiAudioMatrix
import org.jellyfin.androidtv.test.PlaybackMediaDescriptor

class PlaybackHdmiAudioTests : FunSpec({
	fun media(id: String, codec: String, videoCodec: String = "h264") = PlaybackMediaDescriptor(
		id, id, "mkv", 1920, 1080, videoCodec, "high", 41, 8, "SDR", null, emptySet(), setOf(codec),
		audioStreamCount = 1,
		audioStreams = listOf(PlaybackAudioDescriptor(codec, null, 8, 48_000, 24, 1_000_000)),
	)

	test("matrix chooses a playable fixture for each supported HDMI codec") {
		PlaybackHdmiAudioMatrix.plan(listOf(media("truehd", "truehd"), media("eac3", "eac3")), setOf("eac3", "truehd"))
			.map { it.id } shouldBe listOf("passthrough-eac3", "passthrough-truehd")
	}

	test("codec comparison accepts backend aliases") {
		PlaybackHdmiAudioMatrix.codecMatches("dtshd", "dts-hd ma") shouldBe true
		PlaybackHdmiAudioMatrix.codecMatches("truehd", "audio/ac3") shouldBe false
	}
})
