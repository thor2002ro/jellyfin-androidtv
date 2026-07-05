package org.jellyfin.androidtv.ui.playback.overlay.action

import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.TranscodingStatusFormatter
import org.jellyfin.androidtv.ui.playback.getSubtitleMediaStreamCodec
import org.jellyfin.androidtv.util.toIso2LanguageDisplayOrSelf
import org.jellyfin.androidtv.util.withoutUndeterminedLanguagePrefix
import org.jellyfin.playback.media3.exoplayer.subtitle.isSubtitleTimingOffsetSupported
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.TranscodingInfo

object StreamStatusBuilder {
	@JvmStatic
	@JvmOverloads
	fun build(
		playbackController: PlaybackController,
		transcodingInfo: TranscodingInfo? = null,
	) = buildString {
		val streamInfo = playbackController.currentStreamInfo
		val mediaSource = playbackController.currentMediaSource ?: streamInfo?.mediaSource
		val videoStream = mediaSource?.stream(MediaStreamType.VIDEO)
		val audioStream = mediaSource?.stream(MediaStreamType.AUDIO, playbackController.audioStreamIndex)
		val selectedSubtitleStreamIndex = playbackController.subtitleStreamIndex
		val subtitleStream = selectedSubtitleStreamIndex
			.takeIf { it >= 0 }
			?.let { mediaSource?.stream(MediaStreamType.SUBTITLE, it) }
		val showAssStats = subtitleStream?.codec.isAssSubtitleCodec() && !playbackController.isBurningSubtitlesForStatus()

		row("Play", streamInfo?.playMethod?.displayName() ?: "Unknown")
		row("Container", streamInfo?.container ?: mediaSource?.container)
		row("Position", "${playbackController.currentPosition.formatDuration()}/${playbackController.duration.formatDuration()}")
		if (playbackController.playbackSpeed != 1f) row("Speed", "${playbackController.playbackSpeed}x")
		row("Video", videoSummary(videoStream))
		row("V bitrate", videoStream?.bitRate?.formatBitrate())
		row("Audio", audioSummary(audioStream))
		row("A bitrate", audioStream?.bitRate?.formatBitrate())
		row("Sub", subtitleSummary(subtitleStream, playbackController.isBurningSubtitlesForStatus()))
		row("Sub IDs", subtitleIds(subtitleStream))
		row("Sub title", subtitleTitle(subtitleStream))
		row("Sub source", subtitleSource(subtitleStream, playbackController.isBurningSubtitlesForStatus()))
		row("Sub flags", subtitleFlags(subtitleStream))
		row("Sub offset", subtitleOffset(subtitleStream, playbackController.isBurningSubtitlesForStatus(), playbackController.subtitleTimingOffsetUs))
		if (showAssStats) {
			row("ASS extractor", playbackController.subtitleExtractorDebug)
			row("ASS render", playbackController.subtitleRenderDebug)
			row("ASS parser", playbackController.subtitleParserDebug)
			row("ASS path", playbackController.subtitlePathDebug)
		}
		row("Progress", TranscodingStatusFormatter.progress(transcodingInfo))
		row("T speed", TranscodingStatusFormatter.speed(transcodingInfo))
		row("T bitrate", TranscodingStatusFormatter.bitrate(transcodingInfo))
		row("T hardware", TranscodingStatusFormatter.hardwareAcceleration(transcodingInfo))
		row("T video", TranscodingStatusFormatter.videoConversion(transcodingInfo, videoStream?.codec))
		row("T audio", TranscodingStatusFormatter.audioConversion(transcodingInfo, audioStream?.codec))
		row(
			"T subtitle",
			TranscodingStatusFormatter.subtitleConversion(
				transcodingInfo,
				subtitleStream?.codec,
				playbackController.isBurningSubtitlesForStatus(),
				subtitleStream?.deliveryMethod?.toString(),
			)
		)
		row("Reason", TranscodingStatusFormatter.reason(transcodingInfo))
	}

	private fun StringBuilder.row(label: String, value: String?) {
		if (!value.isNullOrBlank()) appendLine("$label: $value")
	}

	private fun MediaSourceInfo.stream(
		type: MediaStreamType,
		index: Int = -1,
	): MediaStream? {
		val streams = mediaStreams.orEmpty()
		return streams.firstOrNull { it.type == type && it.index == index }
			?: streams.getOrNull(index)?.takeIf { it.type == type }
			?: streams.firstOrNull { it.type == type }
	}

	private fun videoSummary(stream: MediaStream?) = buildString {
		if (stream == null) {
			append("unknown")
			return@buildString
		}

		appendInline(resolution(stream))
		appendInline(stream.codec?.uppercase())
		appendInline(stream.videoRange.toString())
		appendInline(stream.realFrameRate?.let { "%.3f fps".format(it) })
		if (stream.isInterlaced) appendInline("Interlaced")
	}

	private fun audioSummary(stream: MediaStream?) = buildString {
		if (stream == null) {
			append("unknown")
			return@buildString
		}

		appendInline(stream.codec?.uppercase())
		stream.channels?.takeIf { it > 0 }?.let { appendInline("${it}ch") }
		appendInline(stream.language.toIso2LanguageDisplayOrSelf())
		appendInline(stream.channelLayout)
	}

	private fun subtitleSummary(
		stream: MediaStream?,
		burningSubtitles: Boolean,
	) = buildString {
		when {
			burningSubtitles -> append("burned")
			stream == null -> append("off")
			else -> {
				appendInline(stream.codec?.uppercase())
				appendInline(stream.language.toIso2LanguageDisplayOrSelf())
				stream.deliveryMethod?.let { appendInline(it.toString()) }
				if (stream.isExternal) appendInline("External")
				if (stream.isForced) appendInline("Forced")
			}
		}
	}

	private fun subtitleIds(stream: MediaStream?): String? = buildString {
		stream?.index?.let { appendInline("stream $it") }
	}.takeIf { it.isNotBlank() }

	private fun subtitleTitle(stream: MediaStream?): String? =
		stream?.displayTitle.withoutUndeterminedLanguagePrefix()
			?: stream?.title.withoutUndeterminedLanguagePrefix()

	private fun subtitleSource(
		stream: MediaStream?,
		burningSubtitles: Boolean,
	): String? = when {
		burningSubtitles -> "Burned in"
		stream == null -> null
		stream.isExternal -> "External renderer"
		stream.deliveryMethod == SubtitleDeliveryMethod.ENCODE -> "Burned in"
		else -> stream.deliveryMethod?.let { "$it renderer" } ?: "Embedded renderer"
	}

	private fun subtitleFlags(stream: MediaStream?): String? = buildString {
		if (stream?.isExternal == true) appendInline("External")
		if (stream?.isDefault == true) appendInline("Default")
		if (stream?.isForced == true) appendInline("Forced")
	}.takeIf { it.isNotBlank() }

	private fun subtitleOffset(
		stream: MediaStream?,
		burningSubtitles: Boolean,
		offsetUs: Long,
	): String? = when {
		stream == null || burningSubtitles -> null
		else -> "${offsetUs.formatSignedSeconds()} ${if (stream.supportsSubtitleOffset()) "supported" else "unsupported"}"
	}

	private fun MediaStream.supportsSubtitleOffset(): Boolean {
		val deliveryMethod = deliveryMethod
		if (deliveryMethod == SubtitleDeliveryMethod.ENCODE || deliveryMethod == SubtitleDeliveryMethod.DROP) return false
		if (codec.isNullOrBlank()) return false

		return isSubtitleTimingOffsetSupported(getSubtitleMediaStreamCodec(this))
	}

	private fun String?.isAssSubtitleCodec(): Boolean = when (this?.lowercase()) {
		"ass", "ssa", "text/x-ssa", "text/ssa", "text/ass", "application/x-ass" -> true
		else -> false
	}

	private fun PlayMethod.displayName() = when (this) {
		PlayMethod.DIRECT_PLAY -> "Direct play"
		PlayMethod.DIRECT_STREAM -> "Direct stream"
		PlayMethod.TRANSCODE -> "Transcoding"
	}

	private fun resolution(stream: MediaStream) = when {
		stream.width != null && stream.height != null -> "${stream.width}x${stream.height}"
		else -> null
	}

	private fun Int.formatBitrate(): String {
		return when {
			this >= 1_000_000 -> "%.1f Mbps".format(this / 1_000_000.0)
			else -> "%.0f Kbps".format(coerceAtLeast(0) / 1_000.0)
		}
	}

	private fun StringBuilder.appendInline(value: String?) {
		if (value.isNullOrBlank()) return
		if (isNotEmpty()) append(' ')
		append(value)
	}

	private fun Long.formatDuration(): String {
		if (this < 0) return "Unknown"
		val totalSeconds = this / 1000
		val hours = totalSeconds / 3600
		val minutes = (totalSeconds % 3600) / 60
		val seconds = totalSeconds % 60
		return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
		else "%d:%02d".format(minutes, seconds)
	}

	private fun Long.formatSignedSeconds(): String = "%+.3fs".format(this / 1_000_000.0)
}
