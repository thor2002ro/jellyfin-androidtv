package org.jellyfin.androidtv.ui.player.video

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import org.jellyfin.playback.core.model.PlaybackFrameStats
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
})
