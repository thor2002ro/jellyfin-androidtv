package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.mediastream.MediaConversionMethod

class PlaybackStatusFormattingTests : FunSpec({
	test("server video copy is direct stream even when audio is transcoded") {
		MediaConversionMethod.Transcode.displayName(isVideoDirect = true) shouldBe "Direct stream"
		MediaConversionMethod.Transcode.displayName(isVideoDirect = false) shouldBe "Transcoding"
		MediaConversionMethod.Transcode.displayName() shouldBe "Transcoding"
		MediaConversionMethod.None.displayName(isVideoDirect = true) shouldBe "Direct play"
		MediaConversionMethod.Remux.displayName() shouldBe "Direct stream"
	}

	test("shared playback status formatting preserves separators and labels") {
		buildString {
			appendInline("one")
			appendInline(null)
			appendInline("two")
		} shouldBe "one two"
		buildString {
			appendStatusPart("one")
			appendStatusPart("")
			appendStatusPart("two")
		} shouldBe "one | two"
		1_500_000.formatBitrate() shouldBe "1.5 Mbps"
		"h264".formatCodec() shouldBe "H264"
		"Text/X-SSA".isAssSubtitleCodec() shouldBe true
		"subrip".isAssSubtitleCodec() shouldBe false
		MediaConversionMethod.Remux.displayName() shouldBe "Direct stream"
	}
})
