package org.jellyfin.androidtv.ui.settings.screen

import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import org.jellyfin.playback.media3.exoplayer.mapping.ffmpegAudioMimeTypes
import org.jellyfin.playback.media3.exoplayer.mapping.ffmpegVideoMimeTypes
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

internal data class FfmpegCodecCapability(
	val section: String,
	val displayName: String,
	val included: Boolean,
	val decoderNames: List<String>,
	val matchedDecoderName: String?,
	val mimeType: String?,
)

@androidx.annotation.OptIn(UnstableApi::class)
internal object FfmpegCodecCapabilities {
	fun getCodecs(): List<FfmpegCodecCapability> {
		val catalog = FfmpegCodecCatalog.items
		if (!FfmpegLibrary.isAvailable()) {
			return catalog
				.map { candidate -> candidate.toCapability(included = false) }
				.sorted()
		}

		val reportedDecoders = FfmpegDecoderProbe.getReportedDecoders()
		val codecCapabilities = catalog.map { candidate ->
			val decoderName = when {
				reportedDecoders != null -> candidate.decoderNames.firstOrNull { decoder -> decoder in reportedDecoders }
				FfmpegDecoderProbe.canProbeDecoders -> candidate.decoderNames.firstOrNull(FfmpegDecoderProbe::hasDecoder)
				else -> null
			}

			val included = decoderName != null ||
				(reportedDecoders == null && !FfmpegDecoderProbe.canProbeDecoders && candidate.mimeType?.let(FfmpegDecoderProbe::supportsFormat) == true)

			candidate.toCapability(
				included = included,
				matchedDecoderName = decoderName,
			)
		}

		val knownDecoderNames = catalog.flatMap { candidate -> candidate.decoderNames }.toSet()
		val uncatalogedCodecs = reportedDecoders.orEmpty()
			.minus(knownDecoderNames)
			.map { decoderName ->
				FfmpegCodecCapability(
					section = "Other",
					displayName = decoderName.displayDecoderName(),
					included = true,
					decoderNames = listOf(decoderName),
					matchedDecoderName = decoderName,
					mimeType = null,
				)
			}

		return (codecCapabilities + uncatalogedCodecs)
			.distinctBy { codec -> "${codec.section}:${codec.displayName}:${codec.decoderNames.joinToString()}" }
			.sorted()
	}

	private fun FfmpegCodecCandidate.toCapability(
		included: Boolean,
		matchedDecoderName: String? = null,
	) = FfmpegCodecCapability(
		section = section,
		displayName = displayName,
		included = included,
		decoderNames = decoderNames,
		matchedDecoderName = matchedDecoderName,
		mimeType = mimeType,
	)

	private fun List<FfmpegCodecCapability>.sorted() = sortedWith(
		compareBy<FfmpegCodecCapability> { codec -> codec.sectionOrder }
			.thenBy { codec -> codec.displayName }
			.thenBy { codec -> codec.decoderNames.firstOrNull().orEmpty() }
	)

	private val FfmpegCodecCapability.sectionOrder
		get() = when (section) {
			"Video" -> 0
			"Audio" -> 1
			else -> 2
		}
}

private data class FfmpegCodecCandidate(
	val section: String,
	val displayName: String,
	val decoderNames: List<String>,
	val mimeType: String? = null,
)

@androidx.annotation.OptIn(UnstableApi::class)
private object FfmpegCodecCatalog {
	private val containerOnlyVideoCodecs = setOf("avi", "mkv", "mp4", "ogv", "webm")

	private val videoDecoderAliases = mapOf(
		"hevc" to listOf("hevc", "h265"),
		"mp2" to listOf("mpeg2video"),
		"mpeg" to listOf("mpeg1video", "mpeg2video"),
	)

	private val audioDecoderAliases = mapOf(
		"dts" to listOf("dca"),
		"mp1" to listOf("mp1", "mp3"),
		"mp2" to listOf("mp2", "mp3"),
	)

	private val extraVideoCandidates = listOf(
		FfmpegCodecCandidate("Video", "MPEG-4 PART 2", listOf("mpeg4")),
		FfmpegCodecCandidate("Video", "MS MPEG-4", listOf("msmpeg4v3", "msmpeg4v2", "msmpeg4v1")),
		FfmpegCodecCandidate("Video", "WMV1", listOf("wmv1")),
		FfmpegCodecCandidate("Video", "WMV2", listOf("wmv2")),
		FfmpegCodecCandidate("Video", "WMV3", listOf("wmv3")),
		FfmpegCodecCandidate("Video", "THEORA", listOf("theora")),
		FfmpegCodecCandidate("Video", "REALVIDEO", listOf("rv40", "rv30", "rv20", "rv10")),
		FfmpegCodecCandidate("Video", "PRORES", listOf("prores")),
		FfmpegCodecCandidate("Video", "FFV1", listOf("ffv1")),
		FfmpegCodecCandidate("Video", "DVVIDEO", listOf("dvvideo")),
	)

	private val extraAudioCandidates = listOf(
		FfmpegCodecCandidate("Audio", "AAC LATM", listOf("aac_latm")),
		FfmpegCodecCandidate("Audio", "APE", listOf("ape")),
		FfmpegCodecCandidate("Audio", "COOK", listOf("cook")),
		FfmpegCodecCandidate("Audio", "MLP", listOf("mlp")),
		FfmpegCodecCandidate("Audio", "SPEEX", listOf("speex")),
		FfmpegCodecCandidate("Audio", "TAK", listOf("tak")),
		FfmpegCodecCandidate("Audio", "TTA", listOf("tta")),
		FfmpegCodecCandidate("Audio", "WAVPACK", listOf("wavpack")),
		FfmpegCodecCandidate("Audio", "WMA LOSSLESS", listOf("wmalossless")),
		FfmpegCodecCandidate("Audio", "WMA PRO", listOf("wmapro")),
		FfmpegCodecCandidate("Audio", "WMA V1", listOf("wmav1")),
		FfmpegCodecCandidate("Audio", "WMA V2", listOf("wmav2")),
	)

	val items: List<FfmpegCodecCandidate> by lazy {
		buildList {
			ffmpegVideoMimeTypes
				.filterKeys { codec -> codec !in containerOnlyVideoCodecs }
				.forEach { (codec, mimeType) ->
					add(
						FfmpegCodecCandidate(
							section = "Video",
							displayName = codec.videoDisplayName(),
							decoderNames = decoderNamesFor(codec, mimeType, videoDecoderAliases),
							mimeType = mimeType,
						)
					)
				}

			ffmpegAudioMimeTypes.forEach { (codec, mimeType) ->
				add(
					FfmpegCodecCandidate(
						section = "Audio",
						displayName = codec.audioDisplayName(),
						decoderNames = decoderNamesFor(codec, mimeType, audioDecoderAliases),
						mimeType = mimeType,
					)
				)
			}

			addAll(extraVideoCandidates)
			addAll(extraAudioCandidates)
		}.distinctBy { candidate -> "${candidate.section}:${candidate.displayName}" }
	}

	private fun decoderNamesFor(
		codec: String,
		mimeType: String,
		aliases: Map<String, List<String>>,
	) = (listOfNotNull(FfmpegDecoderProbe.getCodecName(mimeType)) + aliases.getOrDefault(codec, listOf(codec)))
		.map(::normalizeDecoderName)
		.distinct()
}

@androidx.annotation.OptIn(UnstableApi::class)
private object FfmpegDecoderProbe {
	private val decoderListMethodNames = setOf(
		"getAvailableDecoders",
		"getCodecNames",
		"getDecoderNames",
		"getEnabledDecoders",
		"getSupportedCodecNames",
		"getSupportedDecoders",
		"ffmpegGetCodecNames",
		"ffmpegGetDecoderNames",
	)

	private val decoderProbeMethod = FfmpegLibrary::class.java.findStaticMethod(
		name = "ffmpegHasDecoder",
		parameterType = String::class.java,
	)

	private val codecNameMethod = FfmpegLibrary::class.java.findStaticMethod(
		name = "getCodecName",
		parameterType = String::class.java,
	)

	private val decoderListMethod = FfmpegLibrary::class.java.declaredMethods
		.firstOrNull { method ->
			method.name in decoderListMethodNames &&
				Modifier.isStatic(method.modifiers) &&
				method.parameterTypes.isEmpty()
		}
		?.accessible()

	val canProbeDecoders get() = decoderProbeMethod != null

	fun getCodecName(mimeType: String): String? = codecNameMethod
		?.invokeCatching(mimeType)
		?.toString()
		?.let(::normalizeDecoderName)
		?.takeIf(String::isNotBlank)

	fun getReportedDecoders(): Set<String>? = decoderListMethod
		?.invokeCatching()
		?.let(::normalizeDecoderList)

	fun hasDecoder(decoderName: String): Boolean {
		if (!FfmpegLibrary.isAvailable()) return false

		return decoderProbeMethod
			?.invokeCatching(decoderName)
			?.let { result -> result as? Boolean }
			?: false
	}

	fun supportsFormat(mimeType: String): Boolean = runCatching {
		FfmpegLibrary.supportsFormat(mimeType)
	}.getOrDefault(false)

	private fun Class<*>.findStaticMethod(
		name: String,
		parameterType: Class<*>,
	) = declaredMethods
		.firstOrNull { method ->
			method.name == name &&
				Modifier.isStatic(method.modifiers) &&
				method.parameterTypes.contentEquals(arrayOf(parameterType))
		}
		?.accessible()

	private fun Method.accessible() = apply {
		isAccessible = true
	}

	private fun Method.invokeCatching(vararg args: Any?): Any? = runCatching {
		invoke(null, *args)
	}.getOrNull()
}

private fun normalizeDecoderList(value: Any?): Set<String> = when (value) {
	null -> emptySet()
	is Array<*> -> value.mapNotNull { item -> item?.toString() }
	is Iterable<*> -> value.mapNotNull { item -> item?.toString() }
	is Map<*, *> -> value.keys.mapNotNull { item -> item?.toString() }
	is String -> value.decoderNameTokens()
	else -> emptySet()
}.map(::normalizeDecoderName)
	.filter(String::isNotBlank)
	.toSet()

private fun String.decoderNameTokens(): List<String> {
	val lines = lineSequence()
		.map(String::trim)
		.filter(String::isNotBlank)
		.toList()

	if (lines.size > 1) {
		return lines.mapNotNull { line ->
			val tokens = line.split(Regex("\\s+"))
			when {
				tokens.size > 1 && tokens.first().isFfmpegCapabilityFlag() -> tokens[1]
				tokens.size == 1 -> tokens.first()
				else -> null
			}
		}
	}

	return split(Regex("[,;|\\s]+"))
}

private fun String.isFfmpegCapabilityFlag() = all { flag -> flag in "VASFDTEILSHB.X" }

private fun normalizeDecoderName(name: String) = name
	.trim()
	.trim(',', ';', '|')
	.lowercase(Locale.US)

private fun String.videoDisplayName() = when (this) {
	"av1" -> "AV1"
	"flv" -> "FLV"
	"h263" -> "H.263"
	"h264" -> "H.264 / AVC"
	"hevc" -> "HEVC / H.265"
	"mjpeg" -> "MJPEG"
	"mp2" -> "MPEG-2 VIDEO"
	"mpeg" -> "MPEG VIDEO"
	"rawvideo" -> "RAW VIDEO"
	"vc1" -> "VC-1"
	"vp8" -> "VP8"
	"vp9" -> "VP9"
	else -> displayDecoderName()
}

private fun String.audioDisplayName() = when (this) {
	"ac3" -> "AC3"
	"alac" -> "ALAC"
	"amr_nb" -> "AMR-NB"
	"amr_wb" -> "AMR-WB"
	"dts" -> "DTS / DTS-HD"
	"eac3" -> "EAC3"
	"flac" -> "FLAC"
	"mp1" -> "MP1"
	"mp2" -> "MP2"
	"mp3" -> "MP3"
	"opus" -> "OPUS"
	"pcm_alaw" -> "PCM A-LAW"
	"pcm_mulaw" -> "PCM MU-LAW"
	"truehd" -> "TRUEHD"
	else -> displayDecoderName()
}

private fun String.displayDecoderName() = replace('_', '-').uppercase(Locale.US)
