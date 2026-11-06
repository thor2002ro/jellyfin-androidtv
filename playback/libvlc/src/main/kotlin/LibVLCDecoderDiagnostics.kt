package org.jellyfin.playback.libvlc

import android.os.Process
import android.util.Log
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val LIBVLC_DECODER_SESSION_PREFIX = "JELLYFIN_VLC_DECODER_BEGIN:"
private const val LIBVLC_DECODER_LOG_TAG = "JellyfinVLCDecoder"

internal data class LibVLCDecoderInfo(
	val name: String,
	val type: String?,
)

internal data class LibVLCDecoderDiagnostics(
	val video: LibVLCDecoderInfo? = null,
	val audio: LibVLCDecoderInfo? = null,
	val output: LibVLCAudioOutput? = null,
)

internal data class LibVLCAudioOutput(val format: String, val sampleRate: Int, val channels: Int?)

internal fun libVLCDecoderSessionMarker(sessionId: String) = "$LIBVLC_DECODER_SESSION_PREFIX$sessionId"

internal class LibVLCDecoderLogParser {
	private var accepting = false
	private var video: LibVLCDecoderInfo? = null
	private var audio: LibVLCDecoderInfo? = null
	private var pendingVideoComponent: String? = null
	private var pendingAudioComponent: String? = null
	private var output: LibVLCAudioOutput? = null
	private var sessionId: String? = null

	@Synchronized
	fun beginSession(sessionId: String) {
		this.sessionId = sessionId
		accepting = false
		clearEvidence()
	}

	@Synchronized
	fun endSession() {
		sessionId = null
		accepting = false
		clearEvidence()
	}

	@Synchronized
	fun accept(line: String): Pair<String, LibVLCAudioOutput?>? {
		if (acceptSessionMarker(line)) return if (accepting && line.substringAfter(LIBVLC_DECODER_SESSION_PREFIX).trim() == sessionId) sessionId?.let { it to output } else null
		if (!accepting) return null
		val match = audioOutputPattern.find(line)
		if (match != null) {
			val format = match.groupValues[1].lowercase()
			val bytesPerSample = when (format) {
				"s8", "u8" -> 1
				"s16l", "s16b", "s16n" -> 2
				"s24l", "s24b" -> 3
				"s32l", "s32b", "f32l", "f32b" -> 4
				"f64l", "f64b" -> 8
				else -> null
			}
			val samples = match.groupValues[3].toIntOrNull() ?: 0
			val bytes = match.groupValues[4].toIntOrNull() ?: 0
			val frameBytes = bytesPerSample?.let { it.toLong() * samples } ?: 0
			val channels = if (frameBytes > 0 && bytes > 0 && bytes % frameBytes == 0L) (bytes / frameBytes).toInt().takeIf { it in 1..32 } else null
			output = LibVLCAudioOutput(format, match.groupValues[2].toIntOrNull() ?: 0, channels)
			return sessionId?.let { it to output }
		}
		if (acceptDecoderModule(line)) return null
		acceptAndroidDecoderComponent(line)
		return null
	}

	@Synchronized
	fun snapshot() = LibVLCDecoderDiagnostics(video = video, audio = audio, output = output)

	private fun acceptSessionMarker(line: String): Boolean {
		if (LIBVLC_DECODER_SESSION_PREFIX !in line) return false
		if (sessionId != null && line.substringAfter(LIBVLC_DECODER_SESSION_PREFIX).trim() == sessionId) {
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
		output = null
		video = null
		audio = null
		pendingVideoComponent = null
		pendingAudioComponent = null
	}
}

internal fun libVLCDecoderLogcatCommand(
	processId: Int,
	startedAt: String = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()),
) = listOf(
	"logcat",
	"-T",
	startedAt,
	"--pid=$processId",
	"-v",
	"brief",
)

internal class LibVLCDecoderLogReader(
	private val parser: LibVLCDecoderLogParser,
	private val processId: Int = Process.myPid(),
	private val processStarter: (List<String>) -> java.lang.Process = { command ->
		ProcessBuilder(command)
			.redirectErrorStream(true)
			.start()
	},
	private val markerWriter: (String) -> Unit = { marker -> Log.i(LIBVLC_DECODER_LOG_TAG, marker) },
	private val logSink: (String) -> Unit = {},
	private val onAudioOutputChanged: (String, LibVLCAudioOutput?) -> Unit = { _, _ -> },
) : AutoCloseable {
	@Volatile
	private var closed = false
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
		// Capture from before launching logcat so a busy startup cannot push our marker out of its tail.
		val command = libVLCDecoderLogcatCommand(processId)
		val runningProcess = runCatching { processStarter(command) }
			.onFailure { error -> Timber.w(error, "Unable to start LibVLC decoder log reader") }
			.getOrNull() ?: return
		process = runningProcess
		readerThread = Thread(
			{
				runCatching {
					runningProcess.inputStream.bufferedReader().useLines { lines ->
						lines.forEach { line ->
							parser.accept(line)?.let { (session, output) -> onAudioOutputChanged(session, output) }
							val tag = line.substringAfter('/').substringBefore('(').trim()
							if (tag == "VLC" || tag == "VLC-std" || tag.startsWith("VLC/")) logSink(line)
						}
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

// Read the negotiated output, not the decoder input or the format VLC merely requested.
private val audioOutputPattern = Regex("""audio output:\s+output '([^']+)'\s+(\d+) Hz .*?frame=(\d+) samples/(\d+) bytes""", RegexOption.IGNORE_CASE)

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
