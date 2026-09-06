package org.jellyfin.playback.media3.exoplayer

import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class VideoDecoderTests : FunSpec({
	test("forced video decoder overrides the saved FFmpeg preference") {
		null.prefersFfmpeg(false) shouldBe false
		null.prefersFfmpeg(true) shouldBe true
		VideoDecoder.HARDWARE.prefersFfmpeg(true) shouldBe false
		VideoDecoder.SOFTWARE.prefersFfmpeg(true) shouldBe false
		VideoDecoder.FFMPEG.prefersFfmpeg(false) shouldBe true
	}

	test("Live TV FFmpeg preference does not affect other playback") {
		shouldPreferFfmpeg(false, false, false) shouldBe false
		shouldPreferFfmpeg(false, true, false) shouldBe false
		shouldPreferFfmpeg(false, true, true) shouldBe true
		shouldPreferFfmpeg(true, false, false) shouldBe true
	}

	test("renderer preferences rebuild when Live TV changes FFmpeg selection") {
		val vod = FfmpegRendererPreferences(
			audio = shouldPreferFfmpeg(false, true, false),
			video = shouldPreferFfmpeg(false, true, false),
		)
		val liveTv = FfmpegRendererPreferences(
			audio = shouldPreferFfmpeg(false, true, true),
			video = shouldPreferFfmpeg(false, true, true),
		)

		rendererPreferencesChanged(null, vod) shouldBe true
		rendererPreferencesChanged(vod, vod) shouldBe false
		rendererPreferencesChanged(vod, liveTv) shouldBe true
	}

	test("effective video decoder falls back to the playing default") {
		null.effectiveVideoDecoder(VideoDecoder.HARDWARE, false, null) shouldBe VideoDecoder.HARDWARE
		null.effectiveVideoDecoder(VideoDecoder.HARDWARE, true, null) shouldBe VideoDecoder.FFMPEG
		null.effectiveVideoDecoder(VideoDecoder.HARDWARE, false, "ffmpeg") shouldBe VideoDecoder.FFMPEG
		null.effectiveVideoDecoder(VideoDecoder.HARDWARE, true, "OMX.test.decoder") shouldBe VideoDecoder.HARDWARE
		VideoDecoder.SOFTWARE.effectiveVideoDecoder(VideoDecoder.HARDWARE, true, "ffmpeg") shouldBe VideoDecoder.SOFTWARE
	}

	test("Live TV starts on target buffer or timeout") {
		shouldStartLivePlayback(true, 5_000, 5_000, false) shouldBe true
		shouldStartLivePlayback(true, 4_999, 5_000, false) shouldBe false
		shouldStartLivePlayback(false, 5_000, 5_000, false) shouldBe false
		shouldStartLivePlayback(false, 0, 5_000, true) shouldBe true
	}

	test("Live TV buffer uses Media3 defaults unless configured") {
		targetLiveTvBufferDuration(5.seconds, null) shouldBe null
		targetLiveTvBufferDuration(null, 5.seconds) shouldBe null
		targetLiveTvBufferDuration(10.seconds, null) shouldBe 10.seconds
		targetLiveTvBufferDuration(5.seconds, 10.seconds) shouldBe 10.seconds
		targetLiveTvBufferDuration(15.seconds, 10.seconds) shouldBe 15.seconds
	}

	test("decoder stall requires new input without new output") {
		hasDecoderStalled(10, 5, 11, 5) shouldBe true
		hasDecoderStalled(10, 5, 10, 5) shouldBe false
		hasDecoderStalled(10, 5, 11, 6) shouldBe false
	}

	test("Amlogic devices are matched from Android build fields") {
		isAmlogicDevice(listOf("Google", "Amlogic S905X4")) shouldBe true
		isAmlogicDevice(listOf("c2.amlogic.avc.decoder")) shouldBe true
		isAmlogicDevice(listOf("NVIDIA", "tegra", null)) shouldBe false
	}

	test("Amlogic H264 TS Live TV detects access units without allowing non-IDR keyframes") {
		liveTvTsExtractorFlags(isAmlogic = true, container = "mpegts", videoCodecs = listOf("h264")) shouldBe
			DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
		liveTvTsExtractorFlags(isAmlogic = true, container = "ts", videoCodecs = listOf("avc")) shouldBe
			DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
		liveTvTsExtractorFlags(isAmlogic = true, container = "hls|mpegts", videoCodecs = listOf("avc")) shouldBe
			DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
		liveTvTsExtractorFlags(isAmlogic = true, container = "mpegtsraw", videoCodecs = listOf("avc")) shouldBe
			DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
		liveTvTsExtractorFlags(isAmlogic = true, container = "mpegts", videoCodecs = listOf("avc1.640028")) shouldBe
			DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
		liveTvTsExtractorFlags(isAmlogic = true, container = "mpegts", videoCodecs = listOf("hevc")) shouldBe 0
		liveTvTsExtractorFlags(isAmlogic = true, container = "hls", videoCodecs = listOf("h264")) shouldBe 0
		liveTvTsExtractorFlags(isAmlogic = false, container = "mpegts", videoCodecs = listOf("h264")) shouldBe
			DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
	}
})
