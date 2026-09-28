package org.jellyfin.playback.mpv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LibMPVNetworkErrorTests : FunSpec({
	test("HTTP failures are distinguished from generic MPV failures") {
		mpvPlaybackErrorCode("HTTP error 503 Service Unavailable") shouldBe "MPV_HTTP_ERROR"
		mpvPlaybackErrorCode("Failed to open http://server/video.mkv") shouldBe "MPV_HTTP_ERROR"
		mpvPlaybackErrorCode("unsupported codec") shouldBe "MPV_ERROR"
	}
})
