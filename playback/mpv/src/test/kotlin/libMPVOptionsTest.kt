package org.jellyfin.playback.mpv

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.PlaybackBufferOptions
import org.jellyfin.playback.core.model.PositionInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import `is`.xyz.mpv.MPVNode

class LibMPVOptionsTest : StringSpec({
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
	}

	"mpv HDR mode reports dynamic metadata before transfer characteristics" {
		mpvHdrMode("pq", 8, hasHdr10Plus = true) shouldBe "Dolby Vision (Profile 8)"
		mpvHdrMode("pq", null, hasHdr10Plus = true) shouldBe "HDR10+"
		mpvHdrMode("pq", null, hasHdr10Plus = false) shouldBe "HDR10"
		mpvHdrMode("hlg", null, hasHdr10Plus = false) shouldBe "HLG"
		mpvHdrMode("s-log2", null, hasHdr10Plus = false) shouldBe "Sony S-Log2"
		mpvHdrMode("bt.1886", null, hasHdr10Plus = false) shouldBe "SDR (BT.1886)"
	}

	"MPV HDR pipeline reports conversion stages" {
		mpvHdrPipeline("pq", 8, hasHdr10Plus = false) shouldBe
			"Dolby Vision (Profile 8) \u2192 HDR10/PQ"
	}

	"MPV GPU API uses the observed Android EGL context" {
		mpvGpuApi(currentContext = "android") shouldBe "opengl"
		mpvGpuApi(currentContext = null) shouldBe null
	}

	"resume position is passed to loadfile" {
		728.seconds.mpvStartOption() shouldBe "start=728.0"
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

	"missing MPV stats use a retry backoff" {
		shouldReadLibMPVStatProperty(lastMissNanos = null, nowNanos = 100, retryNanos = 10) shouldBe true
		shouldReadLibMPVStatProperty(lastMissNanos = 100, nowNanos = 109, retryNanos = 10) shouldBe false
		shouldReadLibMPVStatProperty(lastMissNanos = 100, nowNanos = 110, retryNanos = 10) shouldBe true
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
