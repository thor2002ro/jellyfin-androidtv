package org.jellyfin.androidtv.ui.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.util.sdk.isUsable
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.HardwareAccelerationType
import org.jellyfin.sdk.model.api.SessionInfoDto
import org.jellyfin.sdk.model.api.TranscodeReason
import org.jellyfin.sdk.model.api.TranscodingInfo
import java.util.UUID
import kotlin.math.max

class TranscodingStatusRepository(
	private val api: ApiClient,
) {
	private val cacheMutex = Mutex()
	private var cachedAtMillis = 0L
	private var cachedSessions = emptyList<SessionInfoDto>()

	suspend fun getTranscodingInfo(
		playSessionId: String?,
		itemId: UUID?,
		mediaSourceId: String?,
	): TranscodingInfo? {
		if (!api.isUsable) return null

		val sessions = getCachedSessions()

		return sessions.firstTranscodingInfo {
			!playSessionId.isNullOrBlank() && it.id == playSessionId
		} ?: sessions.firstTranscodingInfo {
			!mediaSourceId.isNullOrBlank() && it.playState?.mediaSourceId == mediaSourceId
		} ?: sessions.firstTranscodingInfo {
			itemId != null && it.nowPlayingItem?.id == itemId
		} ?: sessions.firstNotNullOfOrNull { it.transcodingInfo }
	}

	@JvmOverloads
	fun getTranscodingInfoBlocking(
		playSessionId: String? = null,
		itemId: UUID? = null,
		mediaSourceId: String? = null,
	): TranscodingInfo? = runBlocking {
		getTranscodingInfo(playSessionId, itemId, mediaSourceId)
	}

	private suspend fun getCachedSessions(): List<SessionInfoDto> = cacheMutex.withLock {
		val now = System.currentTimeMillis()
		if (now - cachedAtMillis < SESSION_CACHE_MS) return@withLock cachedSessions

		cachedSessions = withContext(Dispatchers.IO) {
			api.sessionApi.getSessions(
				deviceId = api.deviceInfo.id,
				activeWithinSeconds = ACTIVE_WITHIN_SECONDS,
			).content
		}
		cachedAtMillis = now
		cachedSessions
	}

	private inline fun List<SessionInfoDto>.firstTranscodingInfo(
		predicate: (SessionInfoDto) -> Boolean,
	): TranscodingInfo? = firstOrNull { predicate(it) && it.transcodingInfo != null }?.transcodingInfo

	private companion object {
		private const val SESSION_CACHE_MS = 2_500L
		private const val ACTIVE_WITHIN_SECONDS = 30
	}
}

object TranscodingStatusFormatter {
	@JvmStatic
	fun compact(info: TranscodingInfo?): String? {
		if (info == null) return null

		return buildString {
			appendStatusPart(progress(info))
			appendStatusPart(speed(info))
			appendStatusPart(info.hardwareAccelerationType?.takeUnless { it == HardwareAccelerationType.NONE }?.toString())
			appendStatusPart(info.transcodeReasons.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.displayName() }?.let { "Reason: $it" })
		}.takeIf { it.isNotBlank() }
	}

	@JvmStatic
	fun status(info: TranscodingInfo?): String? {
		if (info == null) return null

		return buildString {
			appendInline(progress(info))
			appendInline(speed(info))
			appendInline(info.hardwareAccelerationType?.takeUnless { it == HardwareAccelerationType.NONE }?.toString())
		}.takeIf { it.isNotBlank() }
	}

	@JvmStatic
	fun progress(info: TranscodingInfo?): String? = info
		?.completionPercentage
		?.let { "${max(0.0, it).formatPercent()}%" }

	@JvmStatic
	fun speed(info: TranscodingInfo?): String? = info
		?.framerate
		?.takeIf { it > 0f }
		?.let { "${it.formatFps()} fps" }

	@JvmStatic
	fun bitrate(info: TranscodingInfo?): String? = info
		?.bitrate
		?.takeIf { it > 0 }
		?.formatBitrate()

	@JvmStatic
	fun hardwareAcceleration(info: TranscodingInfo?): String? = info
		?.hardwareAccelerationType
		?.takeUnless { it == HardwareAccelerationType.NONE }
		?.toString()

	@JvmStatic
	fun reason(info: TranscodingInfo?): String? = info
		?.transcodeReasons
		?.takeIf { it.isNotEmpty() }
		?.joinToString(", ") { it.displayName() }

	@JvmStatic
	fun videoConversion(
		info: TranscodingInfo?,
		sourceCodec: String?,
	): String? {
		if (info == null) return null

		return buildString {
			appendCodecConversion(sourceCodec, info.videoCodec, info.isVideoDirect)
			appendStatusPart(videoDetails(info))
		}.takeIf { it.isNotBlank() }
	}

	@JvmStatic
	fun audioConversion(
		info: TranscodingInfo?,
		sourceCodec: String?,
	): String? {
		if (info == null) return null

		return buildString {
			appendCodecConversion(sourceCodec, info.audioCodec, info.isAudioDirect)
			info.audioChannels?.takeIf { it > 0 }?.let { appendStatusPart("${it}ch") }
		}.takeIf { it.isNotBlank() }
	}

	@JvmStatic
	fun subtitleConversion(
		info: TranscodingInfo?,
		sourceCodec: String?,
		isBurnedIn: Boolean,
		deliveryMethod: String? = null,
	): String? {
		if (info == null || sourceCodec.isNullOrBlank()) return null

		return when {
			isBurnedIn -> "${sourceCodec.formatCodec()} -> burned into video"
			!deliveryMethod.isNullOrBlank() -> "${sourceCodec.formatCodec()} ($deliveryMethod)"
			info.transcodeReasons.any { it == TranscodeReason.SUBTITLE_CODEC_NOT_SUPPORTED } ->
				"${sourceCodec.formatCodec()} -> converted by server"

			else -> null
		}
	}

	private fun videoDetails(info: TranscodingInfo?): String? = buildString {
		if (info == null) return@buildString
		when {
			info.width != null && info.height != null -> appendInline("${info.width}x${info.height}")
			info.width != null -> appendInline("${info.width}w")
			info.height != null -> appendInline("${info.height}h")
		}
	}.takeIf { it.isNotBlank() }

	private fun StringBuilder.appendCodecConversion(
		sourceCodec: String?,
		targetCodec: String?,
		isDirect: Boolean,
	) {
		val source = sourceCodec.formatCodec()
		val target = targetCodec.formatCodec()

		when {
			isDirect && source != null -> append("Direct $source")
			isDirect && target != null -> append("Direct $target")
			source != null && target != null -> append("$source -> $target")
			target != null -> append("-> $target")
			source != null -> append("$source -> unknown")
		}
	}

	private fun Double.formatPercent(): String = when {
		this >= 99.95 -> "100"
		this % 1.0 == 0.0 -> "%.0f".format(this)
		else -> "%.1f".format(this)
	}

	private fun Float.formatFps(): String = when {
		this % 1.0f == 0.0f -> "%.0f".format(this)
		else -> "%.1f".format(this)
	}

	private fun TranscodeReason.displayName() = name
		.lowercase()
		.split("_")
		.joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}

internal fun MediaConversionMethod.displayName() = when (this) {
	MediaConversionMethod.None -> "Direct play"
	MediaConversionMethod.Remux -> "Direct stream"
	MediaConversionMethod.Transcode -> "Transcoding"
}

internal fun Int.formatBitrate(): String = when {
	this >= 1_000_000 -> "%.1f Mbps".format(this / 1_000_000.0)
	else -> "%.0f Kbps".format(coerceAtLeast(0) / 1_000.0)
}

internal fun String?.formatCodec(): String? = this
	?.takeIf { it.isNotBlank() }
	?.uppercase()

internal fun String?.isAssSubtitleCodec(): Boolean = when (this?.lowercase()) {
	"ass", "ssa", "text/x-ssa", "text/ssa", "text/ass", "application/x-ass" -> true
	else -> false
}

internal fun StringBuilder.appendInline(value: String?) {
	if (value.isNullOrBlank()) return
	if (isNotEmpty()) append(' ')
	append(value)
}

internal fun StringBuilder.appendStatusPart(value: String?) {
	if (value.isNullOrBlank()) return
	if (isNotEmpty()) append(" | ")
	append(value)
}
