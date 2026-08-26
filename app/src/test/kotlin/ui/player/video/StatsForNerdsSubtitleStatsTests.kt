package org.jellyfin.androidtv.ui.player.video

import io.github.thor2002ro.libdovi.DoviStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.model.PlaybackFrameStats
import org.jellyfin.playback.core.model.PlaybackDoviTransformStats
import org.jellyfin.playback.core.model.VideoGeometry

class StatsForNerdsSubtitleStatsTests : StringSpec({
	"subtitle diagnostics are shown for every renderer" {
		subtitleDiagnosticValues(
			PlaybackFrameStats(
				droppedFrames = 0,
				corruptedFrames = 0,
				subtitleExtractor = "Media3 subtitle companion",
				subtitleRender = "Media3 cues",
				subtitleParser = "DefaultSubtitleParserFactory",
				subtitlePath = "Jellyfin internal stream",
			)
		) shouldContainExactly listOf(
			"Provider" to "Media3 subtitle companion",
			"Renderer" to "Media3 cues",
			"Parser" to "DefaultSubtitleParserFactory",
			"Path" to "Jellyfin internal stream",
		)
	}

	"blank subtitle diagnostics are omitted" {
		subtitleDiagnosticValues(
			PlaybackFrameStats(droppedFrames = 0, corruptedFrames = 0, subtitleExtractor = ""),
		) shouldContainExactly emptyList()
	}

	"video diagnostics show frame resolution and video aspect" {
		videoDiagnosticValues(
			geometry = VideoGeometry(720, 576, 16f / 9f),
			zoomStatus = "Auto → Stretch",
		) shouldContainExactly listOf(
			"Frame" to "720x576",
			"Aspect" to "1.7778:1",
			"Zoom" to "Auto → Stretch",
		)
	}

	"libdovi diagnostics are omitted before concrete evidence" {
		libdoviConversionDiagnostic(null, null) shouldBe null
	}

	"libdovi diagnostics show an observation without planner state" {
		libdoviConversionDiagnostic(
			observed = PlaybackDoviTransformStats("DV P7 FEL", "DV P8.1"),
			failure = null,
		) shouldBe "DV P7 FEL → DV P8.1"
	}

	"libdovi diagnostics show the first concrete failure" {
		libdoviConversionDiagnostic(
			observed = null,
			failure = DoviStatus.RPU_PARSE_FAILED,
		) shouldBe "failed — RPU_PARSE_FAILED"
	}

	"libdovi failure takes precedence over an earlier success observation" {
		libdoviConversionDiagnostic(
			observed = PlaybackDoviTransformStats("DV P7 FEL", "DV P8.1"),
			failure = DoviStatus.REPAIR_FAILED,
		) shouldBe "failed — REPAIR_FAILED"
	}

	"hardware Dolby Vision recovery augments the server conversion reason" {
		formatConversionReason(
			serverReason = "Video codec not supported",
			hardwareDoviDecoderFailed = true,
			isTranscoding = true,
		) shouldBe "Hardware HDR/DV decoder failed; Video codec not supported"
	}

	"hardware Dolby Vision recovery remains explicit without a server reason" {
		formatConversionReason(
			serverReason = null,
			hardwareDoviDecoderFailed = true,
			isTranscoding = true,
		) shouldBe "Hardware HDR/DV decoder failed"
	}

	"ordinary conversion reasons and non-transcoded playback are unchanged" {
		formatConversionReason("Video codec not supported", false, true) shouldBe "Video codec not supported"
		formatConversionReason(null, false, true) shouldBe null
		formatConversionReason("Direct play", true, false) shouldBe "Direct play"
	}

})
