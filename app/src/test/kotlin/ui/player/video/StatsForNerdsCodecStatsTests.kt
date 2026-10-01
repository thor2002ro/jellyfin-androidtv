package org.jellyfin.androidtv.ui.player.video

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.constant.Codec
import org.jellyfin.playback.core.mediastream.MediaConversionMethod

class StatsForNerdsCodecStatsTests : StringSpec({
	"copied audio codecs match their player MIME aliases" {
		listOf(
			"aac" to "audio/mp4a-latm",
			"aac_latm" to "audio/mp4a-latm",
			"mp1" to "audio/mpeg-L1",
			"mp2" to "audio/mpeg-L2",
			"mp3" to "audio/mpeg",
			"dts" to "audio/vnd.dts",
			Codec.Audio.DCA to "audio/vnd.dts",
			Codec.Audio.DTS_HD to "audio/vnd.dts.hd",
			Codec.Audio.EAC3_JOC to "audio/eac3-joc",
			"truehd" to "audio/true-hd",
			"pcm_alaw" to "audio/g711-alaw",
			"pcm_mulaw" to "audio/g711-mlaw",
			"pcm_s16le" to "audio/raw",
			"amr_nb" to "audio/3gpp",
		).forEach { (source, mime) ->
			val directLabel = streamingCodecLabel(source, null, MediaConversionMethod.None)
			streamingCodecLabel(source, mime, MediaConversionMethod.Remux) shouldBe directLabel
		}
	}

	"MIME-only labels use proper codec names" {
		listOf(
			" Audio/MP4A-LATM " to "AAC",
			"audio/vnd.dts.hd" to Codec.Audio.DTS_HD.uppercase(),
			"audio/eac3-joc" to Codec.Audio.EAC3_JOC.uppercase(),
			"video/avc" to "H264",
			"video/av01" to "AV1",
			"video/x-vnd.on2.vp9" to "VP9",
			"application/x-subrip" to "SUBRIP",
		).forEach { (mime, expected) ->
			streamingCodecLabel(null, mime, MediaConversionMethod.None) shouldBe "$expected (direct)"
		}
	}

	"generic server codec names do not hide player profiles or imply conversion" {
		streamingCodecLabel(Codec.Audio.DTS, "audio/vnd.dts.hd", MediaConversionMethod.Remux) shouldBe "DTS_HD (direct)"
		streamingCodecLabel(Codec.Audio.EAC3, "audio/eac3-joc", MediaConversionMethod.Remux) shouldBe "EAC3_JOC (direct)"
		streamingCodecLabel(Codec.Audio.DTS_HD, "audio/ac3", MediaConversionMethod.Remux) shouldBe "DTS_HD -> AC3 (transcoding)"
	}

	"actual conversion and unknown names remain distinct" {
		streamingCodecLabel("aac", "audio/ac3", MediaConversionMethod.Remux) shouldBe "AAC -> AC3 (transcoding)"
		streamingCodecLabel("custom-codec", null, MediaConversionMethod.None) shouldBe "CUSTOM-CODEC (direct)"
		streamingCodecLabel(" ", null, MediaConversionMethod.None) shouldBe null
	}

	"direct stream reports copied codecs as direct and converted audio separately" {
		streamingCodecLabel(
			sourceCodec = "hevc",
			playbackCodec = null,
			conversionMethod = MediaConversionMethod.Remux,
		) shouldBe "HEVC (direct)"
		streamingCodecLabel(
			sourceCodec = "eac3",
			playbackCodec = "audio/ac3",
			conversionMethod = MediaConversionMethod.Remux,
		) shouldBe "EAC3 -> AC3 (transcoding)"
	}
})
