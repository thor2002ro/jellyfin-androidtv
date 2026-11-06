package org.jellyfin.playback.libvlc

import android.os.Process
import android.util.Log
import timber.log.Timber

internal const val LIBVLC_DECODER_SESSION_PREFIX = "JELLYFIN_VLC_DECODER_BEGIN:"
private const val LIBVLC_DECODER_LOG_TAG = "JellyfinVLCDecoder"

internal data class LibVLCDecoderInfo(
	val name: String,
	val type: String?,
)

internal data class LibVLCDecoderDiagnostics(
	val video: LibVLCDecoderInfo? = null,
	val audio: LibVLCDecoderInfo? = null,
)

internal fun libVLCDecoderSessionMarker(sessionId: String) = "$LIBVLC_DECODER_SESSION_PREFIX$sessionId"

internal class LibVLCDecoderLogParser {
	private var expectedMarker: String? = null
	private var accepting = false
	private var video: LibVLCDecoderInfo? = null
	private var audio: LibVLCDecoderInfo? = null
	private var pendingVideoComponent: String? = null
	private var pendingAudioComponent: String? = null

	@Synchronized
	fun beginSession(sessionId: String) {
		expectedMarker = libVLCDecoderSessionMarker(sessionId)
		accepting = false
		clearEvidence()
	}

	@Synchronized
	fun endSession() {
		expectedMarker = null
		accepting = false
		clearEvidence()
	}

	@Synchronized
	fun accept(line: String) {
		if (acceptSessionMarker(line)) return
		if (!accepting) return
		if (acceptDecoderModule(line)) return
		acceptAndroidDecoderComponent(line)
	}

	@Synchronized
	fun snapshot() = LibVLCDecoderDiagnostics(video = video, audio = audio)

	private fun acceptSessionMarker(line: String): Boolean {
		if (LIBVLC_DECODER_SESSION_PREFIX !in line) return false
		if (line.contains(expectedMarker ?: return true)) {
			accepting = true
			clearEvidence()
		}
		return true
	}

	private fun acceptDecoderModule(line: String): Boolean {
		val match = decoderModulePattern.find(line) ?: return false
		val kind = match.groupValues[1].lowercase()
		val module = match.groupValues[2]
		val type = decoderModuleType(module)
		val component = when (kind) {
			"video" -> pendingVideoComponent
			"audio" -> pendingAudioComponent
			else -> null
		}.takeIf { type == "hw" }
		val info = LibVLCDecoderInfo(component ?: module, type)
		when (kind) {
			"video" -> {
				video = info
				if (type != "hw") pendingVideoComponent = null
			}
			"audio" -> {
				audio = info
				if (type != "hw") pendingAudioComponent = null
			}
		}
		return true
	}

	private fun acceptAndroidDecoderComponent(line: String) {
		val component = androidDecoderComponent(line) ?: return
		when (decoderComponentKind(component)) {
			DecoderKind.VIDEO -> {
				pendingVideoComponent = component
				video = LibVLCDecoderInfo(component, "hw")
			}
			DecoderKind.AUDIO -> {
				pendingAudioComponent = component
				audio = LibVLCDecoderInfo(component, "hw")
			}
			null -> Unit
		}
	}

	private fun clearEvidence() {
		video = null
		audio = null
		pendingVideoComponent = null
		pendingAudioComponent = null
	}
}

internal fun libVLCDecoderLogcatCommand(processId: Int) = listOf(
	"logcat",
	"-T",
	"1",
	"--pid=$processId",
	"-v",
	"brief",
)

internal class LibVLCDecoderLogReader(
	private val parser: LibVLCDecoderLogParser,
	processId: Int = Process.myPid(),
	private val processStarter: (List<String>) -> java.lang.Process = { command ->
		ProcessBuilder(command)
			.redirectErrorStream(true)
			.start()
	},
	private val markerWriter: (String) -> Unit = { marker -> Log.i(LIBVLC_DECODER_LOG_TAG, marker) },
) : AutoCloseable {
	@Volatile
	private var closed = false
	private val command = libVLCDecoderLogcatCommand(processId)
	private var process: java.lang.Process? = null
	private var readerThread: Thread? = null

	@Synchronized
	fun beginSession(sessionId: String) {
		parser.beginSession(sessionId)
		if (closed) return
		ensureStarted()
		markerWriter(libVLCDecoderSessionMarker(sessionId))
	}

	fun endSession() = parser.endSession()

	fun snapshot() = parser.snapshot()

	@Synchronized
	override fun close() {
		if (closed) return
		closed = true
		parser.endSession()
		process?.destroy()
		readerThread?.interrupt()
	}

	private fun ensureStarted() {
		if (process != null) return
		val runningProcess = runCatching { processStarter(command) }
			.onFailure { error -> Timber.w(error, "Unable to start LibVLC decoder log reader") }
			.getOrNull() ?: return
		process = runningProcess
		readerThread = Thread(
			{
				runCatching {
					runningProcess.inputStream.bufferedReader().useLines { lines ->
						lines.forEach(parser::accept)
					}
				}.onFailure { error ->
					if (!closed) Timber.w(error, "LibVLC decoder log reader stopped")
				}
			},
			"LibVLC decoder log reader",
		).apply {
			isDaemon = true
			start()
		}
	}
}

private enum class DecoderKind { VIDEO, AUDIO }

private val decoderModulePattern = Regex(
	"""using\s+(video|audio)\s+decoder\s+module\s+[\"']([^\"']+)[\"']""",
	RegexOption.IGNORE_CASE,
)
private val androidDecoderComponentPattern = Regex(
	"""(?:\[|allocate\()((?:OMX|c2)\.[A-Za-z0-9._-]+)""",
	RegexOption.IGNORE_CASE,
)

private val softwareDecoderModules = setOf("avcodec", "dav1d", "aom", "libmpeg2", "mpeg2dec")
private val videoComponentTokens = setOf(
	"avc", "h264", "hevc", "h265", "dvhe", "dvh1", "vp8", "vp9", "av1", "av01", "mpeg2", "mpeg4", "vc1", "vvc",
)
private val audioComponentTokens = setOf(
	"aac", "ac3", "eac3", "ec3", "opus", "vorbis", "mp3", "flac", "dts", "truehd", "mpegh",
)

private fun decoderModuleType(module: String): String? = when (module.lowercase()) {
	"mediacodec", "mediacodec_ndk", "mediacodec_jni", "omxil" -> "hw"
	in softwareDecoderModules -> "sw"
	else -> null
}

private fun androidDecoderComponent(line: String): String? {
	val component = androidDecoderComponentPattern.find(line)?.groupValues?.get(1) ?: return null
	return component.takeIf { value ->
		value.contains("decoder", ignoreCase = true) && !value.contains("encoder", ignoreCase = true)
	}
}

private fun decoderComponentKind(component: String): DecoderKind? {
	val normalized = component.lowercase()
	return when {
		".video." in normalized -> DecoderKind.VIDEO
		".audio." in normalized -> DecoderKind.AUDIO
		videoComponentTokens.any { token -> ".$token." in normalized } -> DecoderKind.VIDEO
		audioComponentTokens.any { token -> ".$token." in normalized } -> DecoderKind.AUDIO
		else -> null
	}
}
