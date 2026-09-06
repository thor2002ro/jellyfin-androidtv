package org.jellyfin.playback.mpv

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import `is`.xyz.mpv.MPVNode
import java.util.Locale

/** Video decoder modes exposed by both the Jellyfin player controls and MPV settings. */
enum class LibMPVVideoDecoder(val mpvValue: String, val label: String) {
	AUTOMATIC("auto-unsafe", "Auto unsafe"),
	AUTO_SAFE("auto-safe", "Auto safe"),
	SOFTWARE("no", "Software"),
	MEDIACODEC("mediacodec", "MediaCodec"),
	MEDIACODEC_COPY("mediacodec-copy", "MediaCodec copy"),
}

internal fun effectiveLibMPVVideoDecoder(
	configured: LibMPVVideoDecoder,
	forced: LibMPVVideoDecoder?,
	softwareForLiveTv: Boolean,
	isLiveTv: Boolean,
	videoPreset: LibMPVVideoPreset = LibMPVVideoPreset.OFF,
) = forced ?: when {
	videoPreset == LibMPVVideoPreset.OPTIMIZED_8K -> LibMPVVideoDecoder.MEDIACODEC
	softwareForLiveTv && isLiveTv -> LibMPVVideoDecoder.SOFTWARE
	else -> configured
}

internal fun LibMPVVideoDecoder.forDoviPlayback(requiresHardwareVideoDecoder: Boolean) =
	if (requiresHardwareVideoDecoder) LibMPVVideoDecoder.MEDIACODEC else this

internal fun effectiveLibMPVVideoOutput(
	configured: String,
	decoder: LibMPVVideoDecoder,
	videoRange: String?,
): String {
	val triesNativeMediaCodec = decoder == LibMPVVideoDecoder.AUTOMATIC ||
		decoder == LibMPVVideoDecoder.AUTO_SAFE ||
		decoder == LibMPVVideoDecoder.MEDIACODEC
	return if (isLibMPVHdrRange(videoRange) && triesNativeMediaCodec) "mediacodec_embed" else configured
}

internal fun isLibMPVHdrRange(videoRange: String?) = !videoRange.isNullOrBlank() &&
	!videoRange.equals("SDR", ignoreCase = true) &&
	!videoRange.equals("UNKNOWN", ignoreCase = true)

internal fun shouldUseNativeSubtitleOverlay(videoRange: String?, videoOutput: String) =
	isLibMPVHdrRange(videoRange) && videoOutput == "mediacodec_embed"

/**
 * The Android-TV-friendly MPV profile. Every field has an explicit Jellyfin default.
 * Additional non-managed libMPV options are supplied through [customOptions].
 */
data class LibMPVPlaybackOptions(
	val videoOutput: String = "gpu-next",
	val gpuContext: String = "android",
	val gpuApi: String = "auto",
	val videoSync: String = "audio",
	val frameDrop: String = "vo",
	val deinterlace: String = "no",
	val interpolation: Boolean = false,
	val scaler: String = "bilinear",
	val deband: Boolean = false,
	val toneMapping: String = "auto",
	val audioOutput: String = "",
	val audioChannels: String = "auto-safe",
	val audioSpdif: String = "",
	val audioPitchCorrection: Boolean = true,
	val replayGain: String = "no",
	val decoderThreads: Int = 0,
	val skipLoopFilter: String = "default",
	val subtitleAssOverride: String = "no",
	val subtitleUseMargins: Boolean = true,
	val softwareDecodingForLiveTv: Boolean = false,
	val nvidiaShieldWorkarounds: Boolean = true,
	val videoPreset: LibMPVVideoPreset = LibMPVVideoPreset.OFF,
	val audioPreset: LibMPVAudioPreset = LibMPVAudioPreset.OFF,
	val customOptions: Map<String, String> = emptyMap(),
) {
	internal fun managedOptions(vulkanSupported: Boolean = true): LinkedHashMap<String, String> {
		val effectiveGpuApi = effectiveLibMPVGpuApi(gpuApi, vulkanSupported)
		val effectiveGpuContext = when {
			gpuApi == "vulkan" && vulkanSupported -> "androidvk,android"
			gpuApi == "vulkan" -> "android"
			else -> gpuContext
		}
		return linkedMapOf(
			"vo" to videoOutput,
			"gpu-context" to effectiveGpuContext,
			"gpu-api" to effectiveGpuApi,
			"video-sync" to videoSync,
			"framedrop" to frameDrop,
			"deinterlace" to deinterlace,
			"interpolation" to interpolation.mpvBoolean(),
			"scale" to scaler,
			"cscale" to scaler,
			"dscale" to scaler,
			"deband" to deband.mpvBoolean(),
			"tone-mapping" to toneMapping,
			"ao" to audioOutput,
			"audio-channels" to audioChannels,
			"audio-spdif" to audioSpdif,
			"audio-pitch-correction" to audioPitchCorrection.mpvBoolean(),
			"replaygain" to replayGain,
			"vd-lavc-threads" to decoderThreads.coerceIn(0, 32).toString(),
			"vd-lavc-skiploopfilter" to skipLoopFilter,
			"sub-ass-override" to subtitleAssOverride,
			"sub-use-margins" to subtitleUseMargins.mpvBoolean(),
		)
	}

	companion object {
		val DEFAULT = LibMPVPlaybackOptions()
	}
}

fun isLibMPVVulkanSupported(context: Context): Boolean =
	Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
		context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_2)

fun isLibMPVVulkanSupported(apiVersion: Int?): Boolean =
	apiVersion != null && apiVersion >= VULKAN_1_2

internal fun effectiveLibMPVGpuApi(requested: String, vulkanSupported: Boolean) =
	when {
		requested != "vulkan" -> requested
		vulkanSupported -> "vulkan,opengl"
		else -> "opengl"
	}

private const val VULKAN_1_2 = (1 shl 22) or (2 shl 12)

/** Metadata reported by the bundled libMPV for a single option. */
data class LibMPVOptionInfo(
	val name: String,
	val type: String?,
	val currentValue: String?,
	val defaultValue: String?,
	val minimum: String?,
	val maximum: String?,
	val choices: List<String>,
	val expectsFile: Boolean,
	val readOnly: Boolean,
)

/** Validation errors that can be inferred safely from libMPV option metadata. */
enum class LibMPVOptionValueError {
	INVALID_FLAG,
	INVALID_INTEGER,
	INVALID_NUMBER,
	BELOW_MINIMUM,
	ABOVE_MAXIMUM,
	INVALID_CHOICE,
}

/**
 * Normalize scalar editor input without changing strings, paths, or list values where
 * whitespace can be meaningful.
 */
fun normalizeLibMPVOptionValue(option: LibMPVOptionInfo, value: String): String {
	if (!option.isScalarOption()) return value
	val normalized = value.trim()
	return if (option.normalizedType().isFlagType()) {
		when (normalized.lowercase(Locale.US)) {
			"yes", "true", "1", "on" -> "yes"
			"no", "false", "0", "off" -> "no"
			else -> normalized
		}
	} else {
		normalized
	}
}

/**
 * Validate only constraints reported by libMPV. Unknown and complex option types remain
 * accepted because libMPV's type metadata is intentionally not a stable schema.
 */
fun validateLibMPVOptionValue(option: LibMPVOptionInfo, rawValue: String): LibMPVOptionValueError? {
	val value = normalizeLibMPVOptionValue(option, rawValue)
	if (value in option.choices) return null

	val type = option.normalizedType()
	when {
		type.isFlagType() -> {
			if (value.lowercase(Locale.US) !in MPV_FLAG_VALUES) return LibMPVOptionValueError.INVALID_FLAG
		}

		type.isIntegerType() -> {
			val number = value.toLongOrNull() ?: return if (option.choices.isNotEmpty()) {
				LibMPVOptionValueError.INVALID_CHOICE
			} else {
				LibMPVOptionValueError.INVALID_INTEGER
			}
			option.minimum?.toLibMPVDoubleOrNull()?.let { minimum ->
				if (number.toDouble() < minimum) return LibMPVOptionValueError.BELOW_MINIMUM
			}
			option.maximum?.toLibMPVDoubleOrNull()?.let { maximum ->
				if (number.toDouble() > maximum) return LibMPVOptionValueError.ABOVE_MAXIMUM
			}
		}

		type.isNumberType() -> {
			val number = value.toLibMPVDoubleOrNull()?.takeUnless(Double::isNaN) ?: return if (option.choices.isNotEmpty()) {
				LibMPVOptionValueError.INVALID_CHOICE
			} else {
				LibMPVOptionValueError.INVALID_NUMBER
			}
			option.minimum?.toLibMPVDoubleOrNull()?.let { minimum ->
				if (number < minimum) return LibMPVOptionValueError.BELOW_MINIMUM
			}
			option.maximum?.toLibMPVDoubleOrNull()?.let { maximum ->
				if (number > maximum) return LibMPVOptionValueError.ABOVE_MAXIMUM
			}
		}

		type.isChoiceType() && option.choices.isNotEmpty() -> {
			// libMPV may omit numeric choices and expose their valid interval only through
			// minimum/maximum metadata. Accept those numeric values when they are in range.
			if (option.minimum == null && option.maximum == null) return LibMPVOptionValueError.INVALID_CHOICE
			val number = value.toLibMPVDoubleOrNull()?.takeUnless(Double::isNaN)
				?: return LibMPVOptionValueError.INVALID_CHOICE
			option.minimum?.toLibMPVDoubleOrNull()?.let { minimum ->
				if (number < minimum) return LibMPVOptionValueError.BELOW_MINIMUM
			}
			option.maximum?.toLibMPVDoubleOrNull()?.let { maximum ->
				if (number > maximum) return LibMPVOptionValueError.ABOVE_MAXIMUM
			}
		}
	}

	return null
}

/** Result of parsing the expert override text. Invalid lines are ignored but reported. */
data class LibMPVOptionOverrides(
	val values: LinkedHashMap<String, String>,
	val invalidLines: List<Int>,
)

private val MPV_FLAG_VALUES = setOf("yes", "no")
private val MPV_OPTION_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")

private fun LibMPVOptionInfo.normalizedType() = type?.trim()?.lowercase(Locale.US).orEmpty()

private fun LibMPVOptionInfo.isScalarOption(): Boolean {
	val normalized = normalizedType()
	return normalized.isFlagType() || normalized.isIntegerType() || normalized.isNumberType() || normalized.isChoiceType()
}

private fun String.isFlagType() = contains("flag") || contains("boolean")
private fun String.isIntegerType() = contains("integer") || this == "int"
private fun String.isNumberType() = contains("double") || contains("float") || contains("number")
private fun String.isChoiceType() = contains("choice")

private fun String.toLibMPVDoubleOrNull(): Double? = when (lowercase(Locale.US)) {
	"inf", "+inf", "infinity", "+infinity" -> Double.POSITIVE_INFINITY
	"-inf", "-infinity" -> Double.NEGATIVE_INFINITY
	"nan" -> Double.NaN
	else -> toDoubleOrNull()
}

/** Parse one `name=value` override per line. Blank lines and `#` comments are ignored. */
fun parseLibMPVOptionOverrides(source: String): LibMPVOptionOverrides {
	val values = linkedMapOf<String, String>()
	val invalidLines = mutableListOf<Int>()

	source.lineSequence().forEachIndexed { index, originalLine ->
		val line = originalLine.trim()
		if (line.isBlank() || line.startsWith('#')) return@forEachIndexed

		val normalized = line.removePrefix("--")
		val separator = normalized.indexOf('=')
		if (separator <= 0) {
			invalidLines += index + 1
			return@forEachIndexed
		}

		val name = normalized.substring(0, separator).trim()
		val value = normalized.substring(separator + 1).trim()
		if (!MPV_OPTION_NAME.matches(name)) {
			invalidLines += index + 1
			return@forEachIndexed
		}
		values[name] = value
	}

	return LibMPVOptionOverrides(values, invalidLines)
}

/** Serialize overrides deterministically for preferences and patches. */
fun serializeLibMPVOptionOverrides(values: Map<String, String>): String = values
	.toSortedMap()
	.entries
	.joinToString("\n") { (name, value) -> "$name=$value" }

internal val MPV_INTERNAL_OPTIONS = setOf(
	"config",
	"config-dir",
	"gpu-shader-cache-dir",
	"icc-cache-dir",
	"idle",
	"osc",
	"osd-level",
	"sub-auto",
	"audio-file-auto",
	"cover-art-auto",
	"autoload-files",
	"keep-open",
	"force-window",
	"input-default-bindings",
	"input-builtin-bindings",
	"input-vo-keyboard",
	"terminal",
	// Universal Jellyfin playback controls.
	"pause",
	"speed",
	"cache",
	"cache-secs",
	"cache-pause",
	"cache-pause-initial",
	"cache-pause-wait",
	"demuxer-max-bytes",
	"demuxer-max-back-bytes",
	"demuxer-readahead-secs",
	"aid",
	"sid",
	"sub-delay",
	"sub-speed",
	"android-surface-size",
	// Universal Jellyfin subtitle appearance.
	"sub-font-size",
	"sub-bold",
	"sub-color",
	"sub-back-color",
	"sub-outline-color",
	"sub-outline-size",
	"sub-shadow-offset",
	"sub-border-style",
	"sub-margin-y",
)

private val MPV_PROFILE_OPTIONS = buildSet {
	add("hwdec")
	addAll(LibMPVPlaybackOptions.DEFAULT.managedOptions().keys)
}

/**
 * True when an option is controlled by a typed MPV setting or by Jellyfin's universal
 * playback UI. Keeping these options read-only in the raw editor prevents two settings
 * surfaces from reporting different active values.
 */
fun isLibMPVOptionManagedByJellyfin(name: String): Boolean =
	name in MPV_INTERNAL_OPTIONS || name in MPV_PROFILE_OPTIONS

internal fun MPVNode?.optionNames(): Set<String> = when (this) {
	is MPVNode.MapNode -> value.keys
	is MPVNode.ArrayNode -> value.mapNotNullTo(linkedSetOf()) { node ->
		when (node) {
			is MPVNode.StringNode -> node.value
			is MPVNode.MapNode -> node.value["name"]?.asString()
			else -> null
		}
	}
	else -> emptySet()
}

internal fun MPVNode?.displayValue(): String? = when (this) {
	null, MPVNode.None -> null
	is MPVNode.StringNode -> value
	is MPVNode.BooleanNode -> value.mpvBoolean()
	is MPVNode.IntNode -> value.toString()
	is MPVNode.DoubleNode -> when {
		value.isNaN() -> "nan"
		value == Double.POSITIVE_INFINITY -> "inf"
		value == Double.NEGATIVE_INFINITY -> "-inf"
		else -> String.format(Locale.US, "%s", value)
	}
	is MPVNode.ByteArrayNode -> "<binary:${value.size}>"
	is MPVNode.ArrayNode -> value.joinToString(",") { node -> node.displayValue().orEmpty() }
	is MPVNode.MapNode -> value.entries.joinToString(",") { (key, node) -> "$key=${node.displayValue().orEmpty()}" }
}

private fun Boolean.mpvBoolean() = if (this) "yes" else "no"
