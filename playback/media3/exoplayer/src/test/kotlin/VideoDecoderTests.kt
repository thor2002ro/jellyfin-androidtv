package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class VideoDecoderTests : FunSpec({
	test("audio fallback disables the failed renderer once and restores it for later playback") {
		val rendererChanges = mutableListOf<Pair<Int, Boolean>>()
		var prepareCount = 0
		val fallback = AudioDecoderFallbackController(
			setRendererDisabled = { rendererIndex, disabled -> rendererChanges += rendererIndex to disabled },
			prepare = { prepareCount++ },
		)

		fallback.tryFallback(
			errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
			rendererIndex = 3,
			isMediaCodecAudioRenderer = true,
			isFfmpegFormatSupported = true,
		) shouldBe true
		fallback.tryFallback(
			errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
			rendererIndex = 3,
			isMediaCodecAudioRenderer = true,
			isFfmpegFormatSupported = true,
		) shouldBe false
		rendererChanges shouldBe listOf(3 to true)
		prepareCount shouldBe 1

		fallback.reset()
		rendererChanges shouldBe listOf(3 to true, 3 to false)
	}

	test("MediaCodec audio decoder failures select FFmpeg fallback when supported") {
		listOf(
			PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
			PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
			PlaybackException.ERROR_CODE_DECODING_FAILED,
			PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
			PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
			PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
		).forEach { errorCode ->
			shouldFallbackToFfmpegAudio(
				errorCode = errorCode,
				isMediaCodecAudioRenderer = true,
				isFfmpegFormatSupported = true,
				fallbackAttempted = false,
			) shouldBe true
		}
	}

	test("audio fallback requires the MediaCodec renderer, FFmpeg format support, and no prior attempt") {
		listOf(
			Triple(false, true, false),
			Triple(true, false, false),
			Triple(true, true, true),
		).forEach { (isMediaCodecAudioRenderer, isFfmpegFormatSupported, fallbackAttempted) ->
			shouldFallbackToFfmpegAudio(
				errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
				isMediaCodecAudioRenderer = isMediaCodecAudioRenderer,
				isFfmpegFormatSupported = isFfmpegFormatSupported,
				fallbackAttempted = fallbackAttempted,
			) shouldBe false
		}
	}

	test("audio output failures do not retry the same sink through FFmpeg") {
		listOf(
			PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
			PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
			PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
			PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
		).forEach { errorCode ->
			shouldFallbackToFfmpegAudio(
				errorCode = errorCode,
				isMediaCodecAudioRenderer = true,
				isFfmpegFormatSupported = true,
				fallbackAttempted = false,
			) shouldBe false
		}
	}

	test("stalled MediaCodec audio retries with FFmpeg after input advances without output") {
		val rendererChanges = mutableListOf<Pair<Int, Boolean>>()
		var prepareCount = 0
		val fallback = AudioDecoderFallbackController(
			setRendererDisabled = { rendererIndex, disabled -> rendererChanges += rendererIndex to disabled },
			prepare = { prepareCount++ },
		)

		fallback.tryFallbackAfterStall(
			rendererIndex = 3,
			isMediaCodecAudioRenderer = true,
			isFfmpegFormatSupported = true,
			decoderStalled = hasAudioDecoderStalled(10, 5, false, 11, 5),
		) shouldBe true
		fallback.tryFallbackAfterStall(
			rendererIndex = 3,
			isMediaCodecAudioRenderer = true,
			isFfmpegFormatSupported = true,
			decoderStalled = true,
		) shouldBe false

		rendererChanges shouldBe listOf(3 to true)
		prepareCount shouldBe 1
	}

	test("audio stall fallback requires decoder input without output and FFmpeg support") {
		listOf(
			Triple(false, true, true),
			Triple(true, false, true),
			Triple(true, true, false),
		).forEach { (isMediaCodecAudioRenderer, isFfmpegFormatSupported, decoderStalled) ->
			val fallback = AudioDecoderFallbackController(
				setRendererDisabled = { _, _ -> error("renderer must remain enabled") },
				prepare = { error("player must not prepare") },
			)

			fallback.tryFallbackAfterStall(
				rendererIndex = 3,
				isMediaCodecAudioRenderer = isMediaCodecAudioRenderer,
				isFfmpegFormatSupported = isFfmpegFormatSupported,
				decoderStalled = decoderStalled,
			) shouldBe false
		}

		hasAudioDecoderStalled(10, 5, false, 10, 5) shouldBe false
		hasAudioDecoderStalled(10, 5, false, 11, 6) shouldBe false
		hasAudioDecoderStalled(10, 5, true, 11, 5) shouldBe false
	}

	test("audio sink veto and watchdog expiry use one atomic observation state") {
		val observation = AudioDecoderStallObservation()

		observation.arm()
		observation.onSinkBufferAttempt()
		observation.expireWithSinkBufferAttempted() shouldBe true

		observation.arm()
		observation.expireWithSinkBufferAttempted() shouldBe false
		observation.onSinkBufferAttempt()
		observation.expireWithSinkBufferAttempted() shouldBe false
	}

	test("video color info is exposed as player metrics") {
		val format = Format.Builder()
			.setColorInfo(
				ColorInfo.Builder()
					.setColorSpace(C.COLOR_SPACE_BT2020)
					.setColorTransfer(C.COLOR_TRANSFER_ST2084)
					.setColorRange(C.COLOR_RANGE_LIMITED)
					.build()
			)
			.build()

		format.colorDetails() shouldBe mapOf(
			"Color space" to "BT.2020",
			"Color transfer" to "PQ (ST 2084)",
			"Color range" to "Limited",
		)
	}

	test("buffer details only show bandwidth while loading") {
		formatExoBufferDetails(1_048_576, false, false, 2_097_152) shouldBe "1.00 MiB, idle"
		formatExoBufferDetails(1_048_576, false, true, 2_097_152) shouldBe
			"1.00 MiB, loading, ~2.00 MiB/s"
	}

	test("forced decoder falls back to the previous working decoder before software") {
		forcedVideoDecoderFallbacks(VideoDecoder.FFMPEG, VideoDecoder.HARDWARE) shouldBe
			listOf(VideoDecoder.HARDWARE, VideoDecoder.SOFTWARE)
		forcedVideoDecoderFallbacks(VideoDecoder.HARDWARE, null) shouldBe
			listOf(VideoDecoder.SOFTWARE, VideoDecoder.FFMPEG)
		forcedVideoDecoderFallbacks(VideoDecoder.SOFTWARE, null) shouldBe
			listOf(VideoDecoder.FFMPEG, VideoDecoder.HARDWARE)
	}

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
		VideoDecoder.HARDWARE.effectiveVideoDecoder(
			VideoDecoder.HARDWARE,
			false,
			"c2.test.decoder",
			fallback = VideoDecoder.SOFTWARE,
		) shouldBe VideoDecoder.SOFTWARE
	}

	test("active Dolby Vision routes use hardware video decoding only") {
		VideoDecoder.HARDWARE.forDoviPlayback(hasDoviDecision = true) shouldBe VideoDecoder.HARDWARE
		VideoDecoder.SOFTWARE.forDoviPlayback(hasDoviDecision = true) shouldBe VideoDecoder.HARDWARE
		VideoDecoder.FFMPEG.forDoviPlayback(hasDoviDecision = true) shouldBe VideoDecoder.HARDWARE
		VideoDecoder.SOFTWARE.forDoviPlayback(hasDoviDecision = false) shouldBe VideoDecoder.SOFTWARE
	}

	test("active Dolby Vision routes request server recovery before a software fallback") {
		shouldRecoverDoviBeforeDecoderFallback(true, VideoDecoder.SOFTWARE) shouldBe true
		shouldRecoverDoviBeforeDecoderFallback(true, VideoDecoder.FFMPEG) shouldBe true
		shouldRecoverDoviBeforeDecoderFallback(true, VideoDecoder.HARDWARE) shouldBe false
		shouldRecoverDoviBeforeDecoderFallback(false, VideoDecoder.SOFTWARE) shouldBe false
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

	test("decoder stalls are watched during active ready and buffering playback") {
		shouldWatchVideoDecoderStall(true, Player.STATE_READY) shouldBe true
		shouldWatchVideoDecoderStall(true, Player.STATE_BUFFERING) shouldBe true
		shouldWatchVideoDecoderStall(false, Player.STATE_READY) shouldBe false
		shouldWatchVideoDecoderStall(true, Player.STATE_IDLE) shouldBe false
	}

	test("audio stall checks only watch an active native decoder") {
		shouldWatchAudioDecoderStall(true, Player.STATE_READY, "c2.android.aac.decoder", false) shouldBe true
		shouldWatchAudioDecoderStall(true, Player.STATE_BUFFERING, "OMX.google.aac.decoder", false) shouldBe true
		shouldWatchAudioDecoderStall(false, Player.STATE_READY, "c2.android.aac.decoder", false) shouldBe false
		shouldWatchAudioDecoderStall(true, Player.STATE_IDLE, "c2.android.aac.decoder", false) shouldBe false
		shouldWatchAudioDecoderStall(true, Player.STATE_READY, "ffmpegaudiodec", false) shouldBe false
		shouldWatchAudioDecoderStall(true, Player.STATE_READY, null, false) shouldBe false
		shouldWatchAudioDecoderStall(true, Player.STATE_READY, "c2.android.aac.decoder", true) shouldBe false
	}

	test("Amlogic devices are matched from Android build fields") {
		isAmlogicDevice(listOf("Google", "Amlogic S905X4")) shouldBe true
		isAmlogicDevice(listOf("c2.amlogic.avc.decoder")) shouldBe true
		isAmlogicDevice(listOf("NVIDIA", "tegra", null)) shouldBe false
	}

	test("Amlogic H264 TS Live TV detects access units without allowing non-IDR keyframes") {
		liveTvTsExtractorFlags(
			isAmlogic = true,
			hardwareVideoDecoding = true,
			container = "mpegts",
			videoCodecs = listOf("h264"),
		) shouldBe
			DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
		liveTvTsExtractorFlags(
			isAmlogic = true,
			hardwareVideoDecoding = true,
			container = "ts",
			videoCodecs = listOf("avc"),
		) shouldBe
			DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
		liveTvTsExtractorFlags(
			isAmlogic = true,
			hardwareVideoDecoding = true,
			container = "hls|mpegts",
			videoCodecs = listOf("avc"),
		) shouldBe
			DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
		liveTvTsExtractorFlags(
			isAmlogic = true,
			hardwareVideoDecoding = true,
			container = "mpegtsraw",
			videoCodecs = listOf("avc"),
		) shouldBe
			DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
		liveTvTsExtractorFlags(
			isAmlogic = true,
			hardwareVideoDecoding = true,
			container = "mpegts",
			videoCodecs = listOf("avc1.640028"),
		) shouldBe
			DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
		liveTvTsExtractorFlags(
			isAmlogic = true,
			hardwareVideoDecoding = false,
			container = "mpegts",
			videoCodecs = listOf("h264"),
		) shouldBe 0
		liveTvTsExtractorFlags(
			isAmlogic = true,
			hardwareVideoDecoding = true,
			container = "mpegts",
			videoCodecs = listOf("hevc"),
		) shouldBe 0
		liveTvTsExtractorFlags(
			isAmlogic = true,
			hardwareVideoDecoding = true,
			container = "hls",
			videoCodecs = listOf("h264"),
		) shouldBe 0
		liveTvTsExtractorFlags(
			isAmlogic = false,
			hardwareVideoDecoding = false,
			container = "mpegts",
			videoCodecs = listOf("h264"),
		) shouldBe
			DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
	}

	test("leaving hardware decoding recreates an Amlogic access-unit source") {
		val flags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS

		shouldRecreateLiveTvMediaSource(flags, VideoDecoder.HARDWARE) shouldBe false
		shouldRecreateLiveTvMediaSource(flags, VideoDecoder.SOFTWARE) shouldBe true
		shouldRecreateLiveTvMediaSource(flags, VideoDecoder.FFMPEG) shouldBe true
		shouldRecreateLiveTvMediaSource(0, VideoDecoder.SOFTWARE) shouldBe false
		shouldRecreateLiveTvMediaSource(null, VideoDecoder.SOFTWARE) shouldBe false
	}
})
