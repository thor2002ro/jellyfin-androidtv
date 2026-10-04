package org.jellyfin.playback.jellyfin.mediastream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DtsEncodingBitrateTests : FunSpec({
	test("DTS encoding raises a low requested bitrate without changing video or escaped parameters") {
		"/Videos/item/master.m3u8?AudioCodec=dts&AudioBitrate=640000&VideoCodec=hevc&Tag=a%2Bb"
			.withDtsEncodingBitrateFloor() shouldBe
			"/Videos/item/master.m3u8?AudioCodec=dts&AudioBitrate=768000&VideoCodec=hevc&Tag=a%2Bb"
	}
	test("DCA encoding adds a missing bitrate and preserves the fragment") {
		"/Audio/item/stream?audioCodec=DCA#part".withDtsEncodingBitrateFloor() shouldBe
			"/Audio/item/stream?audioCodec=DCA&AudioBitrate=768000#part"
	}
	test("higher bitrates and other audio outputs are unchanged") {
		listOf(
			"/stream?AudioCodec=dts&AudioBitrate=1536000",
			"/stream?AudioCodec=dts&AudioBitrate=768000",
			"/stream?AudioCodec=ac3&AudioBitrate=640000",
			"/stream?AudioCodec=copy&AudioBitrate=640000",
			"/stream?AudioCodec=dts,aac&AudioBitrate=640000",
			"/stream",
		).forEach { url -> url.withDtsEncodingBitrateFloor() shouldBe url }
	}
})
