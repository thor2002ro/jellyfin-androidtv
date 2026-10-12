package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.constant.HdrOverrideMode
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.util.profile.getHdrRangeTypesFor
import org.jellyfin.androidtv.util.profile.getUnsupportedHevcVideoRangeWorkarounds
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.playback.core.mediastream.mediaStreamFlow
import org.jellyfin.sdk.model.api.VideoRangeType

@Composable
fun PlaybackInfoOverlay(
	playbackManager: PlaybackManager,
	modifier: Modifier = Modifier,
) {
	val entry by rememberQueueEntry(playbackManager)
	val mediaStream by entry?.mediaStreamFlow?.collectAsState(null) ?: return
	val stream = mediaStream ?: return
	val itemId = entry?.baseItem?.id
	val mediaSourceId = entry?.mediaSourceId
	val isQualityForcedTranscode = entry?.forceTranscoding == true
	val forceTranscodingSourceBitrate = entry?.forceTranscodingSourceBitrate
	val speed by playbackManager.state.speed.collectAsState()
	val subtitleOffset by playbackManager.state.subtitleTimingOffset.collectAsState()
	val softwareCodecsEnabled = userPreferences[UserPreferences.softwareCodecsEnabled]
	val mediaTest = remember(softwareCodecsEnabled) { MediaCodecCapabilitiesTest(softwareCodecsEnabled) }
	val forceEnabledHdr = userPreferences.getHdrRangeTypesFor(HdrOverrideMode.ENABLE)
	val forceDisabledHdr = userPreferences.getHdrRangeTypesFor(HdrOverrideMode.DISABLE)
	var refreshTick by remember { mutableStateOf(0) }
	var positionInfo by remember(playbackManager) { mutableStateOf(playbackManager.state.positionInfo) }
	var frameStats by remember(playbackManager) { mutableStateOf(playbackManager.backend.getFrameStats()) }
	var transcodingInfo by remember(stream.identifier, itemId, mediaSourceId) {
		mutableStateOf<TranscodingInfo?>(null)
	}

	LaunchedEffect(playbackManager) {
		while (true) {
			positionInfo = playbackManager.state.positionInfo
			frameStats = playbackManager.backend.getFrameStats()
			delay(1_000)
			refreshTick++
		}
	}

	LaunchedEffect(stream.identifier, itemId, mediaSourceId, stream.conversionMethod) {
		if (stream.conversionMethod != MediaConversionMethod.Transcode) {
			transcodingInfo = null
			return@LaunchedEffect
		}

		while (true) {
			transcodingInfo = transcodingStatusRepository.getTranscodingInfo(
				playSessionId = stream.identifier,
				itemId = itemId,
				mediaSourceId = mediaSourceId,
			)
			delay(2_000)
		}
	}

	val clientWorkaroundInfo = remember(
		stream,
		mediaTest,
		forceEnabledHdr,
		forceDisabledHdr,
		isQualityForcedTranscode,
		forceTranscodingSourceBitrate,
	) {
		buildClientWorkaroundInfo(
			stream = stream,
			mediaTest = mediaTest,
			forceEnabledHdr = forceEnabledHdr,
			forceDisabledHdr = forceDisabledHdr,
			isQualityForcedTranscode = isQualityForcedTranscode,
			forceTranscodingSourceBitrate = forceTranscodingSourceBitrate,
		)
	}

	val sections = remember(
		stream,
		speed,
		subtitleOffset,
		transcodingInfo,
		clientWorkaroundInfo,
		positionInfo,
		frameStats,
		refreshTick,
	) {
		NewPlayerStreamStatusBuilder.build(
			playbackManager = playbackManager,
			stream = stream,
			speed = speed,
			subtitleOffset = subtitleOffset,
			positionInfo = positionInfo,
			frameStats = frameStats,
			transcodingInfo = transcodingInfo,
			isQualityForcedTranscode = isQualityForcedTranscode,
			clientWorkaroundInfo = clientWorkaroundInfo,
		)
	}

	Column(
		verticalArrangement = Arrangement.spacedBy(4.dp),
		modifier = modifier
			.background(Color.Black.copy(alpha = 0.8f))
			.padding(16.dp)
	) {
		// Play method
		val methodStr = when (stream.conversionMethod) {
			MediaConversionMethod.None -> stringResource(R.string.playback_info_direct_play)
			MediaConversionMethod.Remux -> stringResource(R.string.playback_info_direct_stream)
			MediaConversionMethod.Transcode -> stringResource(R.string.playback_info_transcoding)
		}
		InfoText(stringResource(R.string.playback_info_play_method, methodStr))

		// Container
		InfoText(stringResource(R.string.playback_info_container, stream.container.format.uppercase()))

		// Video info
		if (videoTrack != null) {
			InfoText("")
			InfoText(stringResource(R.string.playback_info_video_title))
			InfoText(stringResource(R.string.playback_info_codec, videoTrack.codec.uppercase()))
			if (videoTrack.width > 0 && videoTrack.height > 0) {
				InfoText(stringResource(R.string.playback_info_resolution, videoTrack.width, videoTrack.height))
			}
			if (videoTrack.bitrate > 0) {
				InfoText(stringResource(R.string.playback_info_bitrate, formatBitrate(videoTrack.bitrate)))
			}
		}

		// Audio info
		if (audioTrack != null) {
			InfoText("")
			InfoText(stringResource(R.string.playback_info_audio_title))
			InfoText(stringResource(R.string.playback_info_codec, audioTrack.codec.uppercase()))
			InfoText(stringResource(R.string.playback_info_channels, audioTrack.channels))
			if (audioTrack.bitrate > 0) {
				InfoText(stringResource(R.string.playback_info_bitrate, formatBitrate(audioTrack.bitrate)))
			}

		return listOf(
			PlaybackInfoSection(
				title = "",
				rows = rows {
					row("Player", "ExoPlayer")
					row("Play method", stream.conversionMethod.displayName())
					row("Protocol", stream.protocol())
					row("Stream type", stream.streamType())
					row("Position", "${positionInfo.active.formatDuration()}/${positionInfo.duration.formatDuration()}")
					row("Buffer", positionInfo.formatBuffer())
					if (speed != 1f) row("Speed", "${"%.2f".format(speed)}x")
				},
			),
			PlaybackInfoSection(
				title = "Streaming Info",
				rows = rows {
					row("Video resolution", resolution(videoTrack?.width, videoTrack?.height))
					row("Dropped frames", frameStats.droppedFrames.toString())
					row("Corrupted frames", frameStats.corruptedFrames.toString())
					row("Video codec", streamingVideoCodec(videoTrack, transcodingInfo))
					row("Audio codec", streamingAudioCodec(audioTrack, selectedAudio, transcodingInfo))
					row("Audio channels", audioTrack?.channels?.takeIf { it > 0 }?.formatChannels())
					row("Audio language", audioLanguage(selectedAudio))
					row("Bitrate", streamBitrate(videoTrack, audioTrack, transcodingInfo))
					row("Conversion speed", TranscodingStatusFormatter.speed(transcodingInfo))
					row("Conversion reason", conversionReason(stream, transcodingInfo, isQualityForcedTranscode))
					row("Workaround", clientWorkaroundInfo)
					row("Transcoding progress", TranscodingStatusFormatter.progress(transcodingInfo))
					row("Hardware acceleration", TranscodingStatusFormatter.hardwareAcceleration(transcodingInfo))
					row(
						"Subtitle conversion",
						TranscodingStatusFormatter.subtitleConversion(
							transcodingInfo,
							selectedSubtitleStream?.codec ?: selectedSubtitle?.codec,
							isBurnedIn = false,
							deliveryMethod = selectedSubtitleStream?.takeIf { it.isExternal }?.let { "External" },
						)
					)
				},
			),
			PlaybackInfoSection(
				title = "Original Media Info",
				rows = rows {
					row("Container", stream.container.format)
					row("Video codec", videoTrack?.codec.formatCodec())
					row("Video bitrate", videoTrack?.bitrate?.takeIf { it > 0 }?.formatBitrate())
					row("Video range", videoTrack?.videoRange)
					row("Audio codec", audioTrack?.codec.formatCodec())
					row("Audio bitrate", audioTrack?.bitrate?.takeIf { it > 0 }?.formatBitrate())
					row("Audio channels", audioTrack?.channels?.takeIf { it > 0 }?.formatChannels())
					row("Audio sample rate", audioTrack?.sampleRate?.takeIf { it > 0 }?.let { "$it Hz" })
					row("Subtitle", subtitleSummary(selectedSubtitle, selectedSubtitleStream, subtitleOffset))
					row("Subtitle lang/delivery", subtitleLanguageDelivery(selectedSubtitle, selectedSubtitleStream))
				},
			),
		).filter { it.rows.isNotEmpty() }
	}

	private inline fun rows(build: MutableList<PlaybackInfoRowModel>.() -> Unit) = buildList(build)

	private fun MutableList<PlaybackInfoRowModel>.row(label: String, value: String?) {
		if (!value.isNullOrBlank()) add(PlaybackInfoRowModel(label, value))
	}

	private fun streamingVideoCodec(
		track: MediaStreamVideoTrack?,
		transcodingInfo: TranscodingInfo?,
	): String? {
		val source = track?.codec.formatCodec()
		val target = transcodingInfo?.videoCodec.formatCodec()

		return when {
			transcodingInfo == null -> source?.let { "$it (direct)" }
			transcodingInfo.isVideoDirect && source != null -> "$source (direct)"
			source != null && target != null -> "$source -> $target"
			target != null -> "-> $target"
			else -> source
		}
	}

	private fun streamingAudioCodec(
		track: MediaStreamAudioTrack?,
		selectedTrack: PlayerTrack?,
		transcodingInfo: TranscodingInfo?,
	): String? {
		val source = (track?.codec ?: selectedTrack?.codec).formatCodec()
		val target = transcodingInfo?.audioCodec.formatCodec()

		return when {
			transcodingInfo == null -> source?.let { "$it (direct)" }
			transcodingInfo.isAudioDirect && source != null -> "$source (direct)"
			source != null && target != null -> "$source -> $target"
			target != null -> "-> $target"
			else -> source
		}

		// Transcoding note
		if (stream.conversionMethod == MediaConversionMethod.Transcode) {
			InfoText("")
			InfoText(stringResource(R.string.playback_info_transcoding_note))
		}
	}
}

private fun buildClientWorkaroundInfo(
	stream: MediaStream,
	mediaTest: MediaCodecCapabilitiesTest,
	forceEnabledHdr: Set<VideoRangeType>,
	forceDisabledHdr: Set<VideoRangeType>,
	isQualityForcedTranscode: Boolean,
	forceTranscodingSourceBitrate: Int?,
): String? {
	if (stream.conversionMethod == MediaConversionMethod.None) return null

	return buildList {
		liveTvQualityWorkaroundInfo(
			isQualityForcedTranscode = isQualityForcedTranscode,
			forceTranscodingSourceBitrate = forceTranscodingSourceBitrate,
		)?.let(::add)

		hevcVideoRangeWorkaroundInfo(
			stream = stream,
			mediaTest = mediaTest,
			forceEnabledHdr = forceEnabledHdr,
			forceDisabledHdr = forceDisabledHdr,
		)?.let(::add)
	}.joinToString("; ").takeIf { it.isNotBlank() }
}

private fun liveTvQualityWorkaroundInfo(
	isQualityForcedTranscode: Boolean,
	forceTranscodingSourceBitrate: Int?,
): String? = when {
	!isQualityForcedTranscode -> null
	forceTranscodingSourceBitrate != null -> "Live TV bitrate: source ${forceTranscodingSourceBitrate.formatWorkaroundBitrate()} over cap"
	else -> "Live TV bitrate: source unknown"
}

private fun hevcVideoRangeWorkaroundInfo(
	stream: MediaStream,
	mediaTest: MediaCodecCapabilitiesTest,
	forceEnabledHdr: Set<VideoRangeType>,
	forceDisabledHdr: Set<VideoRangeType>,
): String? {
	val videoTrack = stream.tracks.filterIsInstance<MediaStreamVideoTrack>().firstOrNull() ?: return null
	if (!videoTrack.isHevc()) return null

	val rangeType = videoTrack.videoRange.toVideoRangeType() ?: return null
	val reason = getUnsupportedHevcVideoRangeWorkarounds(
		mediaTest = mediaTest,
		forceEnabledHdr = forceEnabledHdr,
		forceDisabledHdr = forceDisabledHdr,
	)[rangeType] ?: return null

	return "${rangeType.workaroundLabel()}: $reason"
}

private fun MediaStreamVideoTrack.isHevc(): Boolean =
	codec.equals("hevc", ignoreCase = true) ||
		codec.equals("h265", ignoreCase = true) ||
		codec.equals("h.265", ignoreCase = true)

private fun String?.toVideoRangeType(): VideoRangeType? {
	if (isNullOrBlank()) return null

	return enumValues<VideoRangeType>().firstOrNull { range ->
		equals(range.name, ignoreCase = true) ||
			equals(range.serialName, ignoreCase = true)
	}
}

private fun VideoRangeType.workaroundLabel() = when (this) {
	VideoRangeType.DOVI -> "DV P5"
	VideoRangeType.DOVI_WITH_EL,
	VideoRangeType.DOVI_WITH_ELHDR10_PLUS -> "DV P7"
	VideoRangeType.DOVI_WITH_HDR10,
	VideoRangeType.DOVI_WITH_HDR10_PLUS,
	VideoRangeType.DOVI_WITH_HLG,
	VideoRangeType.DOVI_WITH_SDR -> "DV P8"
	VideoRangeType.HDR10_PLUS -> "HDR10+"
	VideoRangeType.HDR10 -> "HDR10"
	VideoRangeType.DOVI_INVALID -> "DV invalid"
	else -> serialName
}

private fun Int.formatWorkaroundBitrate() = when {
	this >= 1_000_000 -> "%.2f Mbps".format(this / 1_000_000.0)
	else -> "%.0f Kbps".format(coerceAtLeast(0) / 1_000.0)
}

@Composable
private fun InfoText(text: String) {
	Text(
		text = text,
		style = TextStyle(
			color = Color.White,
			fontSize = 14.sp,
		)
	)
}
