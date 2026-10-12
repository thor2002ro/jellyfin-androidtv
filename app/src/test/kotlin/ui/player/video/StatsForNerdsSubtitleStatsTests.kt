package org.jellyfin.androidtv.ui.player.video

import io.github.thor2002ro.libdovi.DoviStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.model.PlaybackFrameStats
import org.jellyfin.playback.core.model.PlaybackDoviTransformStats
import org.jellyfin.playback.core.model.PlaybackDoviTransformProcessor
import org.jellyfin.playback.core.model.VideoGeometry

class StatsForNerdsSubtitleStatsTests : StringSpec({
	"Media3 non ASS diagnostics describe the cue path" {
		subtitleDiagnosticValues(
			PlaybackFrameStats(
				droppedFrames = 0,
				corruptedFrames = 0,
				playerName = "ExoPlayer",
				subtitleExtractor = "AssMatroskaExtractor (MKV)",
				subtitleRender = "libass OpenGL overlay",
				subtitleParser = "AssSubtitleParserFactory",
			),
			isAssSubtitle = false,
		) shouldContainExactly listOf(
			"Provider" to "Media3 default",
			"Renderer" to "Media3 cues",
			"Parser" to "DefaultSubtitleParserFactory",
		)
	}

	"ASS diagnostics preserve the backend values" {
		subtitleDiagnosticValues(
			PlaybackFrameStats(
				droppedFrames = 0,
				corruptedFrames = 0,
				playerName = "ExoPlayer",
				subtitleExtractor = "AssMatroskaExtractor (MKV)",
				subtitleRender = "libass OpenGL overlay",
				subtitleParser = "AssSubtitleParserFactory",
			),
			isAssSubtitle = true,
		) shouldContainExactly listOf(
			"Provider" to "AssMatroskaExtractor (MKV)",
			"Renderer" to "libass OpenGL overlay",
			"Parser" to "AssSubtitleParserFactory",
		)
	}

	"MPV and libVLC diagnostics use the values each backend provides" {
		subtitleDiagnosticValues(
			PlaybackFrameStats(
				droppedFrames = 0,
				corruptedFrames = 0,
				playerName = "libMPV",
				subtitleExtractor = "libMPV",
				subtitleRender = "libass",
				subtitleParser = "subrip",
			),
			isAssSubtitle = false,
		) shouldContainExactly listOf(
			"Provider" to "libMPV",
			"Renderer" to "libass",
			"Parser" to "subrip",
		)
		subtitleDiagnosticValues(
			PlaybackFrameStats(
				droppedFrames = 0,
				corruptedFrames = 0,
				playerName = "libVLC",
				subtitleExtractor = "libVLC",
				subtitleRender = "libVLC",
			),
			isAssSubtitle = false,
		) shouldContainExactly listOf(
			"Provider" to "libVLC",
			"Renderer" to "libVLC",
		)
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

	"Dolby Vision diagnostics identify libdovi processing" {
		libdoviConversionDiagnostic(
			observed = PlaybackDoviTransformStats("DV P7 FEL", "DV P8.1"),
			failure = null,
		) shouldBe "libdovi — DV P7 FEL → DV P8.1"
	}

	"Dolby Vision diagnostics identify fast HDR processing" {
		libdoviConversionDiagnostic(
			observed = PlaybackDoviTransformStats(
				inputPresentation = "DV P8.1",
				outputPresentation = "HDR10",
				processor = PlaybackDoviTransformProcessor.FAST_HDR_BASE,
			),
			failure = null,
		) shouldBe "Fast HDR — DV P8.1 → HDR10"
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
