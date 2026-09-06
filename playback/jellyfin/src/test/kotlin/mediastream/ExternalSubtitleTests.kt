package org.jellyfin.playback.jellyfin.mediastream

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ExternalSubtitleTests : StringSpec({
	"external subtitle codecs map to MIME types" {
		subtitleMimeType("srt") shouldBe "application/x-subrip"
		subtitleMimeType("ass") shouldBe "text/x-ssa"
		subtitleMimeType("webvtt") shouldBe "text/vtt"
		subtitleMimeType("ttml") shouldBe "application/ttml+xml"
		subtitleMimeType("pgssub") shouldBe "application/pgs"
		subtitleMimeType("dvdsub") shouldBe "application/vobsub"
		subtitleMimeType("dvbsub") shouldBe "application/dvbsubs"
	}
})
