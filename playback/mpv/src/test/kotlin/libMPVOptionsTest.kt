package org.jellyfin.playback.mpv

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File
import org.jellyfin.playback.core.PlaybackBufferOptions
import org.jellyfin.playback.core.model.PositionInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import `is`.xyz.mpv.MPVNode

class LibMPVOptionsTest : StringSpec({
	"NVIDIA detection uses manufacturer only" {
		isLibMPVNvidiaDevice("NVIDIA") shouldBe true
		isLibMPVNvidiaDevice("NVIDIA Corporation") shouldBe true
		isLibMPVNvidiaDevice("Google") shouldBe false
	}

	"NVIDIA Hi10P fallback avoids expensive unrelated tuning" {
		libMPVNvidiaFallbackOptions(LibMPVShieldFallback.HI10P) shouldBe linkedMapOf(
			"hwdec" to "no",
			"vo" to "gpu-next",
			"vd-lavc-skiploopfilter" to "nonref",
		)
	}

	"NVIDIA MPEG-2 fallback avoids unrelated Hi10P tuning" {
		libMPVNvidiaFallbackOptions(LibMPVShieldFallback.MPEG2) shouldBe linkedMapOf(
			"hwdec" to "no",
			"vo" to "gpu-next",
		)
	}

	"NVIDIA runtime fallback resync is consumed once" {
		val resync = LibMPVNvidiaFallbackResyncState()

		resync.isPending shouldBe false
		resync.schedule(position = 42.seconds, resumeAfter = true)
		resync.isPending shouldBe true
		resync.consume() shouldBe LibMPVNvidiaFallbackResync(position = 42.seconds, resumeAfter = true)
		resync.isPending shouldBe false
		resync.consume() shouldBe null
	}

	"NVIDIA runtime fallback resync honors the latest pause intent" {
		val resync = LibMPVNvidiaFallbackResyncState()

		resync.schedule(position = 42.seconds, resumeAfter = true)
		resync.updateResumeAfter(false) shouldBe true
		resync.consume() shouldBe LibMPVNvidiaFallbackResync(position = 42.seconds, resumeAfter = false)
		resync.updateResumeAfter(true) shouldBe false
	}

	"NVIDIA live runtime fallback resumes without restoring a position" {
		val resync = LibMPVNvidiaFallbackResyncState()

		resync.schedule(position = null, resumeAfter = true)
		resync.consume() shouldBe LibMPVNvidiaFallbackResync(position = null, resumeAfter = true)
	}

	"NVIDIA fallback configuration selects one decoder transition" {
		libMPVNvidiaFallbackConfigurationChange(LibMPVShieldFallback.HI10P, fallbackAllowed = true) shouldBe
			LibMPVNvidiaFallbackConfigurationChange.PRESERVE
		libMPVNvidiaFallbackConfigurationChange(LibMPVShieldFallback.HI10P, fallbackAllowed = false) shouldBe
			LibMPVNvidiaFallbackConfigurationChange.REMOVE
		libMPVNvidiaFallbackConfigurationChange(activeFallback = null, fallbackAllowed = true) shouldBe
			LibMPVNvidiaFallbackConfigurationChange.NONE
	}

	"NVIDIA fallback removal resyncs only when the decoder changes" {
		libMPVNvidiaFallbackRemovalNeedsResync(LibMPVVideoDecoder.AUTOMATIC.mpvValue) shouldBe true
		libMPVNvidiaFallbackRemovalNeedsResync(LibMPVVideoDecoder.SOFTWARE.mpvValue) shouldBe false
	}

	"MPV command results distinguish native failure" {
		libMPVCommandSucceeded(MPVNode.None) shouldBe true
		libMPVCommandSucceeded(null) shouldBe false
	}

	"resume position is passed to loadfile" {
		728.seconds.mpvStartOption() shouldBe "start=728.0"
	}

	"scrubbing previews with keyframes then settles at the requested position" {
		val scrubbing = LibMPVScrubState()

		scrubbing.begin(5.seconds)
		scrubbing.seek(10.seconds) shouldBe LibMPVSeekRequest(10.seconds, LibMPVSeekPrecision.KEYFRAME)
		scrubbing.finish() shouldBe LibMPVSeekRequest(10.seconds, LibMPVSeekPrecision.EXACT, 5.seconds)
		scrubbing.finish() shouldBe null
	}

	"key repeats preserve the position where scrubbing began" {
		val scrubbing = LibMPVScrubState()

		scrubbing.begin(5.seconds)
		scrubbing.seek(10.seconds)
		scrubbing.begin(10.seconds)
		scrubbing.seek(20.seconds)
		scrubbing.finish() shouldBe LibMPVSeekRequest(20.seconds, LibMPVSeekPrecision.EXACT, 5.seconds)
	}

	"an interrupted scrub cannot make a later seek approximate" {
		val scrubbing = LibMPVScrubState()

		scrubbing.begin(5.seconds)
		scrubbing.seek(10.seconds)
		scrubbing.seek(20.seconds) shouldBe LibMPVSeekRequest(20.seconds, LibMPVSeekPrecision.EXACT)
		scrubbing.finish() shouldBe null
	}

	"reset discards a pending scrub target" {
		val scrubbing = LibMPVScrubState()

		scrubbing.begin(5.seconds)
		scrubbing.seek(10.seconds)
		scrubbing.reset()
		scrubbing.finish() shouldBe null
	}

	"last MPV position survives after the native property becomes unavailable" {
		val last = PositionInfo(120.seconds, 130.seconds, 1_200.seconds)
		mpvPositionInfo(null, null, null, null, last) shouldBe last
		mpvPositionInfo(121.0, 1_200.0, null, 10.0, last) shouldBe
			PositionInfo(121.seconds, 131.seconds, 1_200.seconds)
	}

	"automatic decoder modes expose unsafe as the default and safe separately" {
		LibMPVVideoDecoder.AUTOMATIC.mpvValue shouldBe "auto-unsafe"
		LibMPVVideoDecoder.AUTO_SAFE.mpvValue shouldBe "auto-safe"
	}

	"Shield fallback selects software only for eligible formats" {
		selectLibMPVShieldFallback(
			isNvidiaDevice = true,
			fallbackAllowed = true,
			codec = "h264",
			profile = "High 10",
			pixelFormat = "yuv420p10le",
		) shouldBe LibMPVShieldFallback.HI10P
		selectLibMPVShieldFallback(
			isNvidiaDevice = true,
			fallbackAllowed = true,
			codec = "mpeg2video",
			profile = null,
			pixelFormat = "yuv420p",
		) shouldBe LibMPVShieldFallback.MPEG2
		selectLibMPVShieldFallback(
			isNvidiaDevice = true,
			fallbackAllowed = false,
			codec = "h264",
			profile = "High 10",
			pixelFormat = "yuv420p10le",
		) shouldBe null
		selectLibMPVShieldFallback(
			isNvidiaDevice = false,
			fallbackAllowed = true,
			codec = "h264",
			profile = "High 10",
			pixelFormat = "yuv420p10le",
		) shouldBe null
		selectLibMPVShieldFallback(
			isNvidiaDevice = true,
			fallbackAllowed = true,
			codec = "h264",
			profile = null,
			pixelFormat = null,
			bitDepth = 10,
		) shouldBe LibMPVShieldFallback.HI10P
		selectLibMPVShieldFallback(
			isNvidiaDevice = true,
			fallbackAllowed = true,
			codec = "h264",
			profile = "High",
			pixelFormat = "yuv420p",
			bitDepth = 8,
		) shouldBe null
	}

	"Live TV software decoding yields to an explicit player override" {
		effectiveLibMPVVideoDecoder(
			configured = LibMPVVideoDecoder.AUTOMATIC,
			forced = null,
			softwareForLiveTv = true,
			isLiveTv = true,
		) shouldBe LibMPVVideoDecoder.SOFTWARE
		effectiveLibMPVVideoDecoder(
			configured = LibMPVVideoDecoder.AUTOMATIC,
			forced = LibMPVVideoDecoder.MEDIACODEC,
			softwareForLiveTv = true,
			isLiveTv = true,
		) shouldBe LibMPVVideoDecoder.MEDIACODEC
		effectiveLibMPVVideoDecoder(
			configured = LibMPVVideoDecoder.AUTOMATIC,
			forced = null,
			softwareForLiveTv = true,
			isLiveTv = false,
		) shouldBe LibMPVVideoDecoder.AUTOMATIC
		effectiveLibMPVVideoDecoder(
			configured = LibMPVVideoDecoder.AUTOMATIC,
			forced = null,
			softwareForLiveTv = false,
			isLiveTv = false,
			videoPreset = LibMPVVideoPreset.OPTIMIZED_8K,
		) shouldBe LibMPVVideoDecoder.MEDIACODEC
		effectiveLibMPVVideoDecoder(
			configured = LibMPVVideoDecoder.AUTOMATIC,
			forced = LibMPVVideoDecoder.SOFTWARE,
			softwareForLiveTv = false,
			isLiveTv = false,
			videoPreset = LibMPVVideoPreset.OPTIMIZED_8K,
		) shouldBe LibMPVVideoDecoder.SOFTWARE
	}

	"HDR uses direct output only when the decoder can try native MediaCodec" {
		listOf(
			Triple(LibMPVVideoDecoder.MEDIACODEC, "HDR10", "mediacodec_embed"),
			Triple(LibMPVVideoDecoder.AUTOMATIC, "HDR10", "mediacodec_embed"),
			Triple(LibMPVVideoDecoder.AUTO_SAFE, "HLG", "mediacodec_embed"),
			Triple(LibMPVVideoDecoder.MEDIACODEC, "SDR", "gpu-next"),
			Triple(LibMPVVideoDecoder.MEDIACODEC, "UNKNOWN", "gpu-next"),
			Triple(LibMPVVideoDecoder.MEDIACODEC, null, "gpu-next"),
			Triple(LibMPVVideoDecoder.SOFTWARE, "HDR10", "gpu-next"),
			Triple(LibMPVVideoDecoder.MEDIACODEC_COPY, "HDR10", "gpu-next"),
		).forEach { (decoder, videoRange, expected) ->
			effectiveLibMPVVideoOutput(
				configured = "gpu-next",
				decoder = decoder,
				videoRange = videoRange,
			) shouldBe expected
		}
	}

	"mpv HDR mode reports dynamic metadata before transfer characteristics" {
		mpvHdrMode("pq", 8, hasHdr10Plus = true) shouldBe "Dolby Vision (Profile 8)"
		mpvHdrMode("pq", null, hasHdr10Plus = true) shouldBe "HDR10+"
		mpvHdrMode("pq", null, hasHdr10Plus = false) shouldBe "HDR10"
		mpvHdrMode("hlg", null, hasHdr10Plus = false) shouldBe "HLG"
		mpvHdrMode("s-log2", null, hasHdr10Plus = false) shouldBe "Sony S-Log2"
		mpvHdrMode("bt.1886", null, hasHdr10Plus = false) shouldBe "SDR (BT.1886)"
	}

	"MPV HDR pipeline distinguishes direct converted and unknown Dolby Vision output" {
		mpvHdrPipeline("pq", 8, false, "mediacodec_embed") shouldBe "Dolby Vision (Profile 8)"
		mpvHdrPipeline("pq", 8, false, "gpu-next") shouldBe "Dolby Vision (Profile 8) \u2192 HDR10/PQ"
		mpvHdrPipeline("pq", 8, false, null) shouldBe "Dolby Vision (Profile 8) \u2192 Unknown"
		mpvHdrPipeline("pq", null, false, null) shouldBe "HDR10"
	}

	"MPV GPU API uses the observed Android context" {
		mpvGpuApi(currentContext = "android") shouldBe "opengl"
		mpvGpuApi(currentContext = "androidvk") shouldBe "vulkan"
		mpvGpuApi(currentContext = null) shouldBe null
	}

	"MPV GPU API display includes versions on both sides of a fallback" {
		mpvGpuApiDisplay("vulkan", "opengl", "1.3.0", "ES 3.2") shouldBe
			"vulkan 1.3.0 \u2192 opengl ES 3.2"
		mpvGpuApiDisplay("vulkan", "vulkan", "1.3.0", "1.3.0") shouldBe "vulkan 1.3.0"
		mpvGpuApiDisplay("auto", "vulkan", null, "1.3.0") shouldBe "auto \u2192 vulkan 1.3.0"
		mpvGpuApiDisplay("vulkan", null, "1.3.0", null) shouldBe null
	}

	"MPV output display reports every selected renderer mismatch" {
		mpvSelectionDisplay("gpu-next", "gpu") shouldBe "gpu-next \u2192 gpu"
		mpvSelectionDisplay("gpu-next", "gpu-next") shouldBe "gpu-next"
		mpvSelectionDisplay("gpu-next", null) shouldBe null
	}

	"Vulkan prefers Android Vulkan with an OpenGL fallback" {
		LibMPVPlaybackOptions(gpuApi = "vulkan").managedOptions().let { options ->
			options["gpu-context"] shouldBe "androidvk,android"
			options["gpu-api"] shouldBe "vulkan,opengl"
		}
	}

	"unsupported Vulkan falls back to the Android OpenGL context" {
		LibMPVPlaybackOptions(gpuApi = "vulkan", gpuContext = "auto")
			.managedOptions(vulkanSupported = false) shouldBe
			LibMPVPlaybackOptions(gpuApi = "opengl").managedOptions()
	}

	"Vulkan requires API 1.2" {
		isLibMPVVulkanSupported((1 shl 22) or (1 shl 12)) shouldBe false
		isLibMPVVulkanSupported((1 shl 22) or (2 shl 12)) shouldBe true
	}

	"plain subtitle padding maps to MPV 720p margins" {
		mpvSubtitleMarginY(0.08f) shouldBe 58
		mpvSubtitleMarginY(-1f) shouldBe 0
		mpvSubtitleMarginY(1f) shouldBe 600
	}

	"app subtitle size maps its default to MPV default scale" {
		mpvSubtitleFontSize(24f) shouldBe 38f
		mpvSubtitleFontSize(4f) shouldBe 8f
		mpvSubtitleFontSize(100f) shouldBe 96f
	}

	"Jellyfin MPV defaults produce the complete managed profile" {
		LibMPVPlaybackOptions.DEFAULT.videoPreset shouldBe LibMPVVideoPreset.OFF
		LibMPVPlaybackOptions.DEFAULT.audioPreset shouldBe LibMPVAudioPreset.OFF
		LibMPVPlaybackOptions.DEFAULT.nvidiaShieldWorkarounds shouldBe true
		LibMPVPlaybackOptions.DEFAULT.managedOptions() shouldBe linkedMapOf(
			"vo" to "gpu-next",
			"gpu-context" to "android",
			"gpu-api" to "auto",
			"video-sync" to "audio",
			"framedrop" to "vo",
			"deinterlace" to "no",
			"interpolation" to "no",
			"scale" to "bilinear",
			"cscale" to "bilinear",
			"dscale" to "bilinear",
			"deband" to "no",
			"tone-mapping" to "auto",
			"ao" to "",
			"audio-channels" to "auto-safe",
			"audio-spdif" to "",
			"audio-pitch-correction" to "yes",
			"replaygain" to "no",
			"vd-lavc-threads" to "0",
			"vd-lavc-skiploopfilter" to "default",
			"sub-ass-override" to "no",
			"sub-use-margins" to "yes",
		)
	}

	"all built-in presets apply their upstream options" {
		val shaderDirectory = File("mpv-anime").absoluteFile
		val shaderPaths = { names: List<String> ->
			names.joinToString(File.pathSeparator) { name -> shaderDirectory.resolve(name).absolutePath }
		}
		val anime = linkedMapOf(
			"scale" to "spline64",
			"cscale" to "spline64",
			"dscale" to "spline64",
			"deband" to "yes",
			"deband-iterations" to "2",
			"deband-threshold" to "48",
			"deband-range" to "24",
			"deband-grain" to "2",
		)
		val liveAction = linkedMapOf(
			"scale" to "spline64",
			"cscale" to "spline64",
			"dscale" to "spline64",
			"deband" to "no",
		)
		val expectedVideo = mapOf(
			LibMPVVideoPreset.OFF to emptyMap(),
			LibMPVVideoPreset.ANIME_FAST to anime + ("glsl-shaders" to shaderPaths(LibMPVVideoPreset.ANIME_FAST.shaders)),
			LibMPVVideoPreset.ANIME_BALANCED to anime + ("glsl-shaders" to shaderPaths(LibMPVVideoPreset.ANIME_BALANCED.shaders)),
			LibMPVVideoPreset.ANIME_HQ to anime + ("glsl-shaders" to shaderPaths(LibMPVVideoPreset.ANIME_HQ.shaders)),
			LibMPVVideoPreset.LIVE_ACTION_FAST to liveAction + ("glsl-shaders" to shaderPaths(LibMPVVideoPreset.LIVE_ACTION_FAST.shaders)),
			LibMPVVideoPreset.LIVE_ACTION_BALANCED to liveAction + ("glsl-shaders" to shaderPaths(LibMPVVideoPreset.LIVE_ACTION_BALANCED.shaders)),
			LibMPVVideoPreset.LIVE_ACTION_HQ to liveAction + ("glsl-shaders" to shaderPaths(LibMPVVideoPreset.LIVE_ACTION_HQ.shaders)),
			LibMPVVideoPreset.BATTERY_SAVER to mapOf(
				"glsl-shaders" to "",
				"dither" to "no",
				"deband" to "no",
				"scale" to "bilinear",
				"cscale" to "bilinear",
				"dscale" to "bilinear",
				"correct-downscaling" to "no",
				"interpolation" to "no",
			),
			LibMPVVideoPreset.HDR_HIGH_QUALITY to mapOf(
				"correct-downscaling" to "yes",
				"gamma" to "0.0",
				"contrast" to "0.0",
				"saturation" to "0.0",
				"brightness" to "0.0",
				"tone-mapping" to "clip",
			),
			LibMPVVideoPreset.OPTIMIZED_8K to mapOf(
				"gpu-api" to "opengl",
				"gpu-context" to "android",
				"hwdec" to "mediacodec",
				"vd-lavc-dr" to "yes",
				"vd-queue-enable" to "no",
				"profile" to "high-quality",
				"dither" to "no",
				"deband" to "no",
				"interpolation" to "no",
				"glsl-shaders" to "",
				"vf" to "",
			),
		)

		LibMPVVideoPreset.entries.forEach { preset ->
			LibMPVPlaybackOptions(videoPreset = preset).presetOptions(shaderDirectory) shouldBe expectedVideo.getValue(preset)
		}

		LibMPVPlaybackOptions(audioPreset = LibMPVAudioPreset.STANDARD).presetOptions(shaderDirectory) shouldBe mapOf(
			"audio-channels" to "auto-safe",
			"af" to "",
		)
		LibMPVPlaybackOptions(audioPreset = LibMPVAudioPreset.CINEMA_SPATIAL).presetOptions(shaderDirectory) shouldBe mapOf(
			"audio-channels" to "7.1",
			"audio-spdif" to "",
			"af" to "format=channels=7.1,lavfi=[surround],lavfi=[bass=g=3]",
		)
	}

	"expert overrides accept comments optional prefixes equals signs and empty values" {
		val parsed = parseLibMPVOptionOverrides(
			"""
			# device profile
			--profile=gpu-hq
			script-opts=foo=bar,baz=yes
			ao=
			profile=fast
			""".trimIndent(),
		)

		parsed.invalidLines shouldBe emptyList()
		parsed.values shouldBe linkedMapOf(
			"profile" to "fast",
			"script-opts" to "foo=bar,baz=yes",
			"ao" to "",
		)
	}

	"invalid override lines are reported and ignored" {
		val parsed = parseLibMPVOptionOverrides("valid=yes\nnot valid=no\nmissing-value-separator")

		parsed.values shouldBe linkedMapOf("valid" to "yes")
		parsed.invalidLines.shouldContainExactly(2, 3)
	}

	"override serialization is stable" {
		serializeLibMPVOptionOverrides(mapOf("z" to "last", "a" to "first")) shouldBe "a=first\nz=last"
	}

	"native metadata validation checks flags choices and numeric ranges" {
		val flag = option(type = "Flag", choices = listOf("yes", "no"))
		normalizeLibMPVOptionValue(flag, " true ") shouldBe "yes"
		normalizeLibMPVOptionValue(flag, "off") shouldBe "no"
		validateLibMPVOptionValue(flag, " yes ") shouldBe null
		validateLibMPVOptionValue(flag, "maybe") shouldBe LibMPVOptionValueError.INVALID_FLAG

		val integer = option(type = "Integer", minimum = "0.0", maximum = "32.0", choices = listOf("auto"))
		validateLibMPVOptionValue(integer, "auto") shouldBe null
		validateLibMPVOptionValue(integer, "-1") shouldBe LibMPVOptionValueError.BELOW_MINIMUM
		validateLibMPVOptionValue(integer, "33") shouldBe LibMPVOptionValueError.ABOVE_MAXIMUM
		validateLibMPVOptionValue(integer, "many") shouldBe LibMPVOptionValueError.INVALID_CHOICE

		val choice = option(type = "Choice", choices = listOf("auto", "gpu", "gpu-next"))
		validateLibMPVOptionValue(choice, "vdpau") shouldBe LibMPVOptionValueError.INVALID_CHOICE

		val numericChoice = option(type = "Choice", minimum = "0", maximum = "3", choices = listOf("auto", "no"))
		validateLibMPVOptionValue(numericChoice, "2") shouldBe null
		validateLibMPVOptionValue(numericChoice, "4") shouldBe LibMPVOptionValueError.ABOVE_MAXIMUM
		validateLibMPVOptionValue(numericChoice, "unknown") shouldBe LibMPVOptionValueError.INVALID_CHOICE
	}

	"complex option values preserve whitespace and are left to libMPV" {
		val stringOption = option(type = "String")
		normalizeLibMPVOptionValue(stringOption, "  a value  ") shouldBe "  a value  "
		validateLibMPVOptionValue(stringOption, "anything libMPV accepts") shouldBe null

		val scalar = option(type = "Double", minimum = "0", maximum = "2")
		normalizeLibMPVOptionValue(scalar, " 1.25 ") shouldBe "1.25"
		validateLibMPVOptionValue(scalar, "2.5") shouldBe LibMPVOptionValueError.ABOVE_MAXIMUM
		validateLibMPVOptionValue(scalar, "nan") shouldBe LibMPVOptionValueError.INVALID_NUMBER
	}

	"root native option nodes yield names from map and array shapes" {
		MPVNode.MapNode(mapOf("vo" to MPVNode.StringNode("gpu"), "ao" to MPVNode.StringNode("")))
			.optionNames() shouldBe setOf("vo", "ao")

		MPVNode.ArrayNode(
			arrayOf(
				MPVNode.StringNode("speed"),
				MPVNode.MapNode(mapOf("name" to MPVNode.StringNode("video-sync"))),
			),
		).optionNames() shouldBe setOf("speed", "video-sync")
	}

	"buffer settings map all playback thresholds without undersizing the cache" {
		PlaybackBufferOptions(
			minBufferDuration = 8.seconds,
			maxBufferDuration = 5.seconds,
			bufferForPlaybackDuration = 10.seconds,
			bufferForPlaybackAfterRebufferDuration = 15.seconds,
		).toLibMPVBufferConfiguration(isLiveTv = false) shouldBe LibMPVBufferConfiguration(
			cacheSeconds = 15.0,
			initialWaitSeconds = 10.0,
			rebufferWaitSeconds = 15.0,
		)
	}

	"live TV buffer overrides general thresholds" {
		PlaybackBufferOptions(
			minBufferDuration = 2.seconds,
			maxBufferDuration = 30.seconds,
			bufferForPlaybackDuration = 4.seconds,
			bufferForPlaybackAfterRebufferDuration = 6.seconds,
			liveTvBufferDuration = 3.seconds,
		).toLibMPVBufferConfiguration(isLiveTv = true) shouldBe LibMPVBufferConfiguration(
			cacheSeconds = 3.0,
			initialWaitSeconds = 3.0,
			rebufferWaitSeconds = 3.0,
		)
	}

	"missing non-positive and infinite buffer values use native cache defaults" {
		PlaybackBufferOptions(
			minBufferDuration = Duration.ZERO,
			maxBufferDuration = Duration.INFINITE,
			bufferForPlaybackDuration = (-1).seconds,
		).toLibMPVBufferConfiguration(isLiveTv = false) shouldBe LibMPVBufferConfiguration(
			cacheSeconds = null,
			initialWaitSeconds = null,
			rebufferWaitSeconds = null,
		)
	}

	"MPV byte cache covers the requested duration with bitrate headroom" {
		mpvCacheBytes(cacheSeconds = 120.0, bitrate = 20_000_000, maximum = 512_000_000) shouldBe 375_000_000
		mpvCacheBytes(cacheSeconds = 240.0, bitrate = 100_000_000, maximum = 256_000_000) shouldBe 256_000_000
		mpvCacheBytes(cacheSeconds = 120.0, bitrate = 0, maximum = 512_000_000) shouldBe 512_000_000
		mpvCacheBytes(cacheSeconds = null, bitrate = 20_000_000, maximum = 512_000_000) shouldBe null
		mpvCacheBytes(cacheSeconds = 120.0, bitrate = 20_000_000, maximum = null) shouldBe null
	}

	"MPV byte cap also limits cache and wait durations" {
		val cappedSeconds = 128 * 1024 * 1024 * 8.0 / 25_000_000

		LibMPVBufferConfiguration(
			cacheSeconds = 120.0,
			initialWaitSeconds = 2.5,
			rebufferWaitSeconds = 5.0,
		).cappedToBytes(
			bitrate = 25_000_000,
			maximum = 128L * 1024 * 1024,
		) shouldBe LibMPVBufferConfiguration(
			cacheSeconds = cappedSeconds,
			initialWaitSeconds = 2.5,
			rebufferWaitSeconds = 5.0,
		)

		LibMPVBufferConfiguration(
			cacheSeconds = 120.0,
			initialWaitSeconds = 50.0,
			rebufferWaitSeconds = 60.0,
		).cappedToBytes(
			bitrate = 25_000_000,
			maximum = 128L * 1024 * 1024,
		) shouldBe LibMPVBufferConfiguration(
			cacheSeconds = cappedSeconds,
			initialWaitSeconds = cappedSeconds,
			rebufferWaitSeconds = cappedSeconds,
		)
	}

	"missing MPV stats use a retry backoff" {
		shouldReadLibMPVStatProperty(lastMissNanos = null, nowNanos = 100, retryNanos = 10) shouldBe true
		shouldReadLibMPVStatProperty(lastMissNanos = 100, nowNanos = 109, retryNanos = 10) shouldBe false
		shouldReadLibMPVStatProperty(lastMissNanos = 100, nowNanos = 110, retryNanos = 10) shouldBe true
	}

	"typed profile and universal controls cannot be replaced by expert overrides" {
		isLibMPVOptionManagedByJellyfin("speed") shouldBe true
		isLibMPVOptionManagedByJellyfin("sub-color") shouldBe true
		isLibMPVOptionManagedByJellyfin("sub-margin-y") shouldBe true
		isLibMPVOptionManagedByJellyfin("cache-pause-wait") shouldBe true
		isLibMPVOptionManagedByJellyfin("demuxer-readahead-secs") shouldBe true
		isLibMPVOptionManagedByJellyfin("deinterlace") shouldBe true
		isLibMPVOptionManagedByJellyfin("vo") shouldBe true
		isLibMPVOptionManagedByJellyfin("hwdec") shouldBe true
		isLibMPVOptionManagedByJellyfin("sub-ass-override") shouldBe true
		isLibMPVOptionManagedByJellyfin("demuxer-max-bytes") shouldBe true
		isLibMPVOptionManagedByJellyfin("demuxer-max-back-bytes") shouldBe true
	}

	"MPV buffer details suppress stale speed while idle" {
		formatLibMPVBufferDetails(1_048_576, false, true, 2_097_152.0) shouldBe "1.00 MiB, idle"
		formatLibMPVBufferDetails(1_048_576, true, false, 2_097_152.0) shouldBe "1.00 MiB, paused, 2.00 MiB/s"
	}
})

private fun option(
	type: String? = null,
	minimum: String? = null,
	maximum: String? = null,
	choices: List<String> = emptyList(),
) = LibMPVOptionInfo(
	name = "test-option",
	type = type,
	currentValue = null,
	defaultValue = null,
	minimum = minimum,
	maximum = maximum,
	choices = choices,
	expectsFile = false,
	readOnly = false,
)
