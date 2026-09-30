package org.jellyfin.androidtv.ui.player.video

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.HdrOverrideMode
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.playback.TranscodingStatusFormatter
import org.jellyfin.androidtv.ui.playback.TranscodingStatusRepository
import org.jellyfin.androidtv.ui.playback.appendInline
import org.jellyfin.androidtv.ui.playback.displayName
import org.jellyfin.androidtv.ui.playback.formatCodec
import org.jellyfin.androidtv.ui.playback.isAssSubtitleCodec
import org.jellyfin.androidtv.util.apiclient.getTrickplayTileSheets
import org.jellyfin.androidtv.util.toIso2LanguageDisplayOrSelf
import org.jellyfin.androidtv.util.profile.MediaCodecCapabilitiesTest
import org.jellyfin.androidtv.util.profile.DISPLAY_HDR_TYPE_DOLBY_VISION
import org.jellyfin.androidtv.util.profile.DISPLAY_HDR_TYPE_HDR10
import org.jellyfin.androidtv.util.profile.DISPLAY_HDR_TYPE_HDR10_PLUS
import org.jellyfin.androidtv.util.profile.DISPLAY_HDR_TYPE_HLG
import org.jellyfin.androidtv.util.profile.getHdrRangeTypesFor
import org.jellyfin.androidtv.util.profile.getSupportedDisplayHdrTypes
import org.jellyfin.androidtv.util.profile.getUnsupportedHevcVideoRangeWorkarounds
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.PlayerTrack
import org.jellyfin.playback.core.backend.TrackType
import org.jellyfin.playback.core.mediastream.ExternalSubtitle
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStreamFlow
import org.jellyfin.playback.core.model.PlaybackFrameStats
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.model.VideoSize
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.jellyfin.playback.jellyfin.queue.forceTranscoding
import org.jellyfin.playback.jellyfin.queue.forceTranscodingSourceBitrate
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.TranscodingInfo
import org.jellyfin.sdk.model.api.VideoRangeType
import org.koin.compose.koinInject
import kotlin.time.Duration

@Composable
fun PlaybackInfoOverlay(
	playbackManager: PlaybackManager,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val transcodingStatusRepository = koinInject<TranscodingStatusRepository>()
	val userPreferences = koinInject<UserPreferences>()
	val api = koinInject<ApiClient>()
	val density = LocalDensity.current
	val entry by rememberQueueEntry(playbackManager)
	val mediaStream by entry?.mediaStreamFlow?.collectAsState(null) ?: return
	val stream = mediaStream ?: return
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value
	val itemId = item?.id ?: entry?.baseItem?.id
	val mediaSourceId = entry?.mediaSourceId
	val isQualityForcedTranscode = entry?.forceTranscoding == true
	val forceTranscodingSourceBitrate = entry?.forceTranscodingSourceBitrate
	val speed by playbackManager.state.speed.collectAsState()
	val playerVideoSize by playbackManager.state.videoSize.collectAsState()
	val subtitleOffset by playbackManager.state.subtitleTimingOffset.collectAsState()
	val subtitleSpeed by playbackManager.state.subtitleTimingSpeed.collectAsState()
	val subtitleOffsetSupported by playbackManager.state.subtitleTimingOffsetSupported.collectAsState()
	val softwareCodecsEnabled = userPreferences[UserPreferences.softwareCodecsEnabled]
	val parseSubtitlesDuringExtraction = userPreferences[UserPreferences.libassParseSubtitlesDuringExtraction]
	val mediaTest = remember(softwareCodecsEnabled) { MediaCodecCapabilitiesTest(softwareCodecsEnabled) }
	val forceEnabledHdr = userPreferences.getHdrRangeTypesFor(HdrOverrideMode.ENABLE)
	val forceDisabledHdr = userPreferences.getHdrRangeTypesFor(HdrOverrideMode.DISABLE)
	val displayHdrModes = remember(context, mediaTest) { getDisplayHdrModes(context, mediaTest) }
	var refreshTick by remember { mutableStateOf(0) }
	var positionInfo by remember(playbackManager, stream.identifier) { mutableStateOf(playbackManager.state.positionInfo) }
	var frameStats by remember(playbackManager, stream.identifier) { mutableStateOf(playbackManager.backend.getFrameStats()) }
	var transcodingInfo by remember(stream.identifier, itemId, mediaSourceId) {
		mutableStateOf<TranscodingInfo?>(null)
	}

	LaunchedEffect(playbackManager, stream.identifier) {
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
		playerVideoSize,
		subtitleOffset,
		subtitleSpeed,
		subtitleOffsetSupported,
		parseSubtitlesDuringExtraction,
		transcodingInfo,
		clientWorkaroundInfo,
		positionInfo,
		frameStats,
		refreshTick,
		displayHdrModes,
	) {
		NewPlayerStreamStatusBuilder.build(
			playbackManager = playbackManager,
			stream = stream,
			speed = speed,
			playerVideoSize = playerVideoSize,
			subtitleOffset = subtitleOffset,
			subtitleSpeed = subtitleSpeed,
			subtitleOffsetSupported = subtitleOffsetSupported,
			parseSubtitlesDuringExtraction = parseSubtitlesDuringExtraction,
			positionInfo = positionInfo,
			frameStats = frameStats,
			transcodingInfo = transcodingInfo,
			isQualityForcedTranscode = isQualityForcedTranscode,
			clientWorkaroundInfo = clientWorkaroundInfo,
			displayHdrModes = displayHdrModes,
		)
	}

	val chapterThumbnailWidth = with(density) { ChapterThumbnailWidth.roundToPx() }
	val chapterThumbnailHeight = with(density) { ChapterThumbnailHeight.roundToPx() }
	val trickplayCacheUrls = remember(item?.id, item?.trickplay, mediaSourceId, api.accessToken) {
		item?.getTrickplayTileSheets(api, mediaSourceId).orEmpty().map { sheet -> sheet.url }
	}
	val chapterCacheUrls = remember(item?.id, item?.chapters, api.accessToken, chapterThumbnailWidth, chapterThumbnailHeight) {
		item?.getChapterThumbnailUrls(api, chapterThumbnailWidth, chapterThumbnailHeight).orEmpty()
	}
	val thumbnailCacheRows = remember(trickplayCacheUrls, chapterCacheUrls, refreshTick) {
		buildThumbnailCacheRows(
			trickplayUrls = trickplayCacheUrls,
			chapterUrls = chapterCacheUrls,
		)
	}

	Row(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(4.dp),
		verticalAlignment = Alignment.Top,
	) {
		Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
			PlaybackPerformanceOverlay(
				stream = stream,
				frameStats = frameStats,
			)
			PlaybackThumbnailCachePanel(rows = thumbnailCacheRows)
		}

		PlaybackInfoTextPanel(sections = sections)
	}
}

@Composable
private fun PlaybackThumbnailCachePanel(
	rows: List<PlaybackInfoRowModel>,
) {
	Column(
		modifier = Modifier
			.width(146.dp)
			.background(Color.Black.copy(alpha = 0.82f))
			.padding(horizontal = 5.dp, vertical = 4.dp),
		verticalArrangement = Arrangement.spacedBy(3.dp),
	) {
		Text(
			text = "Thumbnail Cache",
			style = TextStyle(
				color = Color.White,
				fontSize = 7.sp,
				lineHeight = 8.sp,
				fontFamily = FontFamily.Monospace,
				fontWeight = FontWeight.W700,
			),
		)
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(start = 4.dp),
		) {
			rows.forEach { row -> PlaybackInfoRow(row) }
		}
	}
}

@Composable
private fun PlaybackInfoTextPanel(
	sections: List<PlaybackInfoSection>,
) {
	Column(
		modifier = Modifier
			.width(190.dp)
			.background(Color.Black.copy(alpha = 0.88f))
			.padding(horizontal = 5.dp, vertical = 4.dp),
		verticalArrangement = Arrangement.spacedBy(3.dp),
	) {
		Text(
			text = "Playback Info",
			style = TextStyle(
				color = Color.White,
				fontSize = 7.sp,
				lineHeight = 8.sp,
				fontFamily = FontFamily.Monospace,
				fontWeight = FontWeight.W700,
			),
		)

		sections.forEachIndexed { index, section ->
			if (index > 0) Spacer(modifier = Modifier.size(1.dp))
			PlaybackInfoSectionContent(section)
		}
	}
}

@Composable
private fun PlaybackInfoSectionContent(section: PlaybackInfoSection) {
	if (section.title.isNotBlank()) {
		Text(
			text = section.title,
			style = TextStyle(
				color = Color.White,
				fontSize = 6.5.sp,
				lineHeight = 7.5.sp,
				fontFamily = FontFamily.Monospace,
				fontWeight = FontWeight.W700,
			),
		)
	}

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = 4.dp),
		verticalArrangement = Arrangement.spacedBy(0.dp),
	) {
		section.rows.forEach { row ->
			PlaybackInfoRow(row)
		}
	}
}

private object NewPlayerStreamStatusBuilder {
	fun build(
		playbackManager: PlaybackManager,
		stream: MediaStream,
		speed: Float,
		playerVideoSize: VideoSize,
		subtitleOffset: Duration,
		subtitleSpeed: Float,
		subtitleOffsetSupported: Boolean,
		parseSubtitlesDuringExtraction: Boolean,
		positionInfo: PositionInfo,
		frameStats: PlaybackFrameStats,
		transcodingInfo: TranscodingInfo?,
		isQualityForcedTranscode: Boolean,
		clientWorkaroundInfo: String?,
		displayHdrModes: String,
	): List<PlaybackInfoSection> {
		val videoTrack = stream.tracks.filterIsInstance<MediaStreamVideoTrack>().firstOrNull()
		val trackSelection = playbackManager.trackSelection
		val selectedAudio = trackSelection
			?.getAvailableTracks(TrackType.AUDIO)
			?.firstOrNull(PlayerTrack::isSelected)
		val audioTrack = stream.tracks
			.filterIsInstance<MediaStreamAudioTrack>()
			.selectedTrack(selectedAudio)
		val subtitleTracks = trackSelection
			?.getAvailableTracks(TrackType.SUBTITLE)
			.orEmpty()
		val selectedSubtitle = subtitleTracks.firstOrNull(PlayerTrack::isSelected)
		val selectedSubtitleStream = stream.tracks
			.filterIsInstance<MediaStreamSubtitleTrack>()
			.firstOrNull { subtitle ->
				subtitle.index == selectedSubtitle?.streamIndex || subtitle.index == selectedSubtitle?.index
			}
		val selectedExternalSubtitle = (stream as? PlayableMediaStream)
			?.externalSubtitles
			?.firstOrNull { externalSubtitle ->
				externalSubtitle.index == selectedSubtitleStream?.index ||
					externalSubtitle.index == selectedSubtitle?.streamIndex ||
					externalSubtitle.index == selectedSubtitle?.index
			}
		val selectedSubtitleCodec = subtitleCodec(selectedSubtitle, selectedSubtitleStream, selectedExternalSubtitle)
		val showAssStats = selectedSubtitle != null && selectedSubtitleCodec.isAssSubtitleCodec()

		return listOf(
			PlaybackInfoSection(
				title = "",
				rows = rows {
					row("Player", "ExoPlayer")
					row("Play method", stream.conversionMethod.displayName())
					row("Protocol", stream.protocol())
					row("Stream type", stream.streamType())
					row("Display HDR", displayHdrModes)
					row("Position", "${positionInfo.active.formatDuration()}/${positionInfo.duration.formatDuration()}")
					row("Buffer", positionInfo.formatBuffer())
					if (speed != 1f) row("Speed", "${"%.2f".format(speed)}x")
				},
			),
			PlaybackInfoSection(
				title = "Streaming Info",
				rows = rows {
					row("Player resolution", playerVideoSize.resolution())
					row("Video decoder", frameStats.videoDecoderLabel())
					row("Dropped frames", frameStats.droppedFrames.toString())
					row("Corrupted frames", frameStats.corruptedFrames.toString())
					row("Video codec", streamingVideoCodec(videoTrack, transcodingInfo, stream.conversionMethod) ?: frameStats.videoCodec)
					row("HDR mode", streamingHdrMode(frameStats.videoHdrMode, videoTrack, transcodingInfo))
					row("Audio decoder", frameStats.audioDecoderLabel())
					row("Audio codec", streamingAudioCodec(audioTrack, selectedAudio, transcodingInfo, stream.conversionMethod))
					row("Audio passthrough", frameStats.audioPassthroughSupported.formatPassthroughSupport())
					row("Audio channels", audioTrack?.channels?.takeIf { it > 0 }?.formatChannels())
					row("Audio language", audioLanguage(selectedAudio))
					row("Bitrate", streamBitrate(videoTrack, audioTrack, transcodingInfo))
					row("Conversion speed", TranscodingStatusFormatter.speed(transcodingInfo))
					row("Conversion reason", conversionReason(stream, transcodingInfo, isQualityForcedTranscode))
					row("Workaround", clientWorkaroundInfo)
					row("Extractor flags", frameStats.extractorFlags)
					row("Transcoding progress", TranscodingStatusFormatter.progress(transcodingInfo))
					row("Hardware acceleration", TranscodingStatusFormatter.hardwareAcceleration(transcodingInfo))
					row(
						"Subtitle conversion",
						TranscodingStatusFormatter.subtitleConversion(
							transcodingInfo,
							selectedSubtitleCodec,
							isBurnedIn = false,
							deliveryMethod = subtitleDeliveryLabel(selectedSubtitleStream, selectedExternalSubtitle),
						)
					)
				},
			),
			PlaybackInfoSection(
				title = "Subtitle Info",
				rows = rows {
					row("Status", subtitleStatus(selectedSubtitle, selectedSubtitleStream))
					row("IDs", subtitleIds(selectedSubtitle, selectedSubtitleStream))
					row("Title", selectedSubtitle?.label ?: selectedSubtitleStream?.title ?: selectedExternalSubtitle?.title)
					row("Codec", selectedSubtitleCodec.formatCodec())
					row("Language", (selectedSubtitle?.language ?: selectedSubtitleStream?.language ?: selectedExternalSubtitle?.language).toIso2LanguageDisplayOrSelf())
					row("Source", subtitleSource(selectedSubtitleStream, selectedExternalSubtitle, parseSubtitlesDuringExtraction))
					if (showAssStats) {
						row("ASS extractor", frameStats.subtitleExtractor)
						row("ASS render", frameStats.subtitleRender)
						row("ASS parser", frameStats.subtitleParser)
						row("ASS path", frameStats.subtitlePath)
					}
					row("Flags", subtitleFlags(selectedSubtitleStream, selectedExternalSubtitle))
					row(
						"Timing",
						subtitleTimingInfo(
							selectedSubtitle,
							selectedSubtitleStream,
							subtitleOffset,
							subtitleSpeed,
							subtitleOffsetSupported,
						),
					)
				},
			),
			PlaybackInfoSection(
				title = "Original Media Info",
				rows = rows {
					row("Container", stream.container.format)
					row("Resolution", resolution(videoTrack?.width, videoTrack?.height))
					row("Video codec", videoTrack?.codec.formatCodec() ?: frameStats.videoCodec)
					row("Video bitrate", videoTrack?.bitrate?.takeIf { it > 0 }?.formatBitrate())
					row("Video FPS", videoTrack?.realFrameRate?.takeIf { it > 0f }?.formatFrameRate())
					row("Video range", videoTrack?.videoRange)
					if (videoTrack?.isInterlaced == true) row("Interlaced", "Yes")
					row("Audio codec", audioTrack?.codec.formatCodec())
					row("Audio bitrate", audioTrack?.bitrate?.takeIf { it > 0 }?.formatBitrate())
					row("Audio channels", audioTrack?.channels?.takeIf { it > 0 }?.formatChannels())
					row("Audio sample rate", audioTrack?.sampleRate?.takeIf { it > 0 }?.let { "$it Hz" })
				},
			),
		).filter { it.rows.isNotEmpty() }
	}

	private inline fun rows(build: MutableList<PlaybackInfoRowModel>.() -> Unit) = buildList(build)

	private fun PlaybackFrameStats.videoDecoderLabel() = videoDecoderName?.let { name ->
		videoDecoderType?.let { type -> "$name ($type)" } ?: name
	}

	private fun PlaybackFrameStats.audioDecoderLabel() = audioDecoderName?.let { name ->
		audioDecoderType?.let { type -> "$name ($type)" } ?: name
	}

	private fun MutableList<PlaybackInfoRowModel>.row(label: String, value: String?) {
		if (!value.isNullOrBlank()) add(PlaybackInfoRowModel(label, value))
	}

	private fun streamingVideoCodec(
		track: MediaStreamVideoTrack?,
		transcodingInfo: TranscodingInfo?,
		conversionMethod: MediaConversionMethod,
	): String? {
		val source = track?.codec.formatCodec()
		val target = transcodingInfo?.videoCodec.formatCodec()

		return when {
			transcodingInfo == null -> source?.withKnownPath(conversionMethod)
			transcodingInfo.isVideoDirect && source != null -> "$source (remux)"
			source != null && target != null -> "$source -> $target (transcoding)"
			target != null -> "-> $target (transcoding)"
			else -> source
		}
	}

	private fun streamingHdrMode(
		hdrMode: String?,
		track: MediaStreamVideoTrack?,
		transcodingInfo: TranscodingInfo?,
	): String? = hdrMode ?: track
		?.videoRange
		?.toVideoRangeType()
		?.takeIf { transcodingInfo?.isVideoDirect != false }
		?.workaroundLabel()

	private fun streamingAudioCodec(
		track: MediaStreamAudioTrack?,
		selectedTrack: PlayerTrack?,
		transcodingInfo: TranscodingInfo?,
		conversionMethod: MediaConversionMethod,
	): String? {
		val source = (selectedTrack?.codec ?: track?.codec).formatCodec()
		val target = transcodingInfo?.audioCodec.formatCodec()

		return when {
			transcodingInfo == null -> source?.withKnownPath(conversionMethod)
			transcodingInfo.isAudioDirect && source != null -> "$source (remux)"
			source != null && target != null -> "$source -> $target (transcoding)"
			target != null -> "-> $target (transcoding)"
			else -> source
		}
	}

	private fun audioLanguage(selectedTrack: PlayerTrack?): String? =
		selectedTrack?.language.toIso2LanguageDisplayOrSelf()

	private fun subtitleStatus(
		track: PlayerTrack?,
		stream: MediaStreamSubtitleTrack?,
	): String = when {
		track == null && stream == null -> "Off"
		else -> "Selected"
	}

	private fun subtitleIds(
		track: PlayerTrack?,
		stream: MediaStreamSubtitleTrack?,
	): String? = buildString {
		track?.index?.let { appendInline("track $it") }
		(stream?.index ?: track?.streamIndex)?.let { appendInline("stream $it") }
		track?.let { appendInline("group ${it.groupIndex}/${it.trackIndex}") }
	}.takeIf { it.isNotBlank() }

	private fun subtitleCodec(
		track: PlayerTrack?,
		stream: MediaStreamSubtitleTrack?,
		externalSubtitle: ExternalSubtitle?,
	): String? = track?.codec ?: stream?.codec ?: externalSubtitle?.mimeType

	private fun subtitleDeliveryLabel(
		stream: MediaStreamSubtitleTrack?,
		externalSubtitle: ExternalSubtitle?,
	): String? = when {
		externalSubtitle != null || stream?.isExternal == true -> "External"
		stream != null -> "Embedded"
		else -> null
	}

	private fun subtitleSource(
		stream: MediaStreamSubtitleTrack?,
		externalSubtitle: ExternalSubtitle?,
		parseSubtitlesDuringExtraction: Boolean,
	): String? = when {
		stream == null && externalSubtitle == null -> null
		externalSubtitle != null || stream?.isExternal == true -> "External renderer"
		parseSubtitlesDuringExtraction -> "Embedded extractor"
		else -> "Embedded renderer"
	}

	private fun subtitleFlags(
		stream: MediaStreamSubtitleTrack?,
		externalSubtitle: ExternalSubtitle?,
	): String? = buildString {
		if (stream?.isExternal == true || externalSubtitle != null) appendInline("External")
		if (externalSubtitle?.isDefault == true) appendInline("Default")
		if (externalSubtitle?.isForced == true) appendInline("Forced")
	}.takeIf { it.isNotBlank() }

	private fun subtitleTimingInfo(
		track: PlayerTrack?,
		stream: MediaStreamSubtitleTrack?,
		offset: Duration,
		speed: Float,
		supported: Boolean,
	): String? = when {
		track == null && stream == null -> null
		else -> "${offset.formatSignedSeconds()} @ ${"%.3f".format(speed)}x " +
			if (supported) "supported" else "unsupported"
	}

	private fun streamBitrate(
		videoTrack: MediaStreamVideoTrack?,
		audioTrack: MediaStreamAudioTrack?,
		transcodingInfo: TranscodingInfo?,
	): String? = TranscodingStatusFormatter.bitrate(transcodingInfo)
		?: listOfNotNull(
			videoTrack?.bitrate?.takeIf { it > 0 },
			audioTrack?.bitrate?.takeIf { it > 0 },
		).takeIf { it.isNotEmpty() }?.sum()?.formatBitrate()

	private fun MediaStream.protocol(): String? {
		val stream = this as? PlayableMediaStream ?: return null
		return Uri.parse(stream.url).scheme
	}

	private fun MediaStream.streamType(): String? {
		val stream = this as? PlayableMediaStream ?: return container.format.uppercase()
		val uri = Uri.parse(stream.url)
		val path = uri.path.orEmpty().lowercase()
		val segmentContainer = runCatching { uri.getQueryParameter("segmentContainer") }.getOrNull()

		return when {
			path.endsWith(".m3u8") || path.contains("/hls/") || segmentContainer != null -> "HLS"
			else -> container.format.uppercase()
		}
	}

	private fun MediaStream.transcodeReasonFromUrl(): String? {
		if (conversionMethod != MediaConversionMethod.Transcode) return null

		val stream = this as? PlayableMediaStream ?: return null
		return runCatching {
			Uri.parse(stream.url).getQueryParameter("TranscodeReasons")
		}.getOrNull()
			?.split(',')
			?.mapNotNull { reason -> reason.trim().takeIf { it.isNotBlank() }?.displayTranscodeReason() }
			?.takeIf { it.isNotEmpty() }
			?.joinToString(", ")
	}

	private fun conversionReason(
		stream: MediaStream,
		transcodingInfo: TranscodingInfo?,
		isQualityForcedTranscode: Boolean,
	): String? = when {
		isQualityForcedTranscode && stream.conversionMethod == MediaConversionMethod.Transcode -> "Bitrate limit"
		else -> TranscodingStatusFormatter.reason(transcodingInfo) ?: stream.transcodeReasonFromUrl()
	}

	private fun String.displayTranscodeReason() = replace('_', ' ')
		.replace(Regex("([a-z])([A-Z])"), "$1 $2")
		.lowercase()
		.split(' ')
		.filter(String::isNotBlank)
		.joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

	private fun MediaConversionMethod.codecPathLabel() = when (this) {
		MediaConversionMethod.None -> "direct"
		MediaConversionMethod.Remux -> "remux"
		MediaConversionMethod.Transcode -> "transcoding"
	}

	private fun String.withKnownPath(conversionMethod: MediaConversionMethod) = when (conversionMethod) {
		MediaConversionMethod.Transcode -> this
		else -> "$this (${conversionMethod.codecPathLabel()})"
	}

	private fun resolution(width: Int?, height: Int?) = when {
		width != null && height != null && width > 0 && height > 0 -> "${width}x$height"
		else -> null
	}

	private fun VideoSize.resolution() = resolution(width, height)

	private fun Int.formatBitrate() = when {
		this >= 1_000_000 -> "%.2f Mbps".format(this / 1_000_000.0)
		else -> "%.0f Kbps".format(coerceAtLeast(0) / 1_000.0)
	}

	private fun Int.formatChannels() = when (this) {
		1 -> "1"
		2 -> "2"
		6 -> "5.1"
		8 -> "7.1"
		else -> toString()
	}

	private fun Float.formatFrameRate() = "%.3f fps".format(this)

	private fun Boolean?.formatPassthroughSupport() = when (this) {
		true -> "Yes"
		false -> "No"
		null -> null
	}

	private fun Duration.formatSignedSeconds(): String = "%+.3fs".format(inWholeMilliseconds / 1000.0)

}

internal fun List<MediaStreamAudioTrack>.selectedTrack(selectedTrack: PlayerTrack?): MediaStreamAudioTrack? =
	firstOrNull { track -> track.index == selectedTrack?.streamIndex } ?: firstOrNull()

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

private fun getDisplayHdrModes(
	context: Context,
	mediaTest: MediaCodecCapabilitiesTest,
): String =
	formatDisplayHdrModes(
		supportedHdrTypes = getSupportedDisplayHdrTypes(context),
		dolbyVisionModes = getDolbyVisionModes(mediaTest),
	)

internal fun formatDisplayHdrModes(
	supportedHdrTypes: Set<Int>,
	dolbyVisionModes: String = "None",
): String =
	supportedHdrTypes
		.map { type ->
			when (type) {
				DISPLAY_HDR_TYPE_DOLBY_VISION -> formatDolbyVisionDisplayMode(dolbyVisionModes)
				DISPLAY_HDR_TYPE_HDR10 -> "HDR10"
				DISPLAY_HDR_TYPE_HDR10_PLUS -> "HDR10+"
				DISPLAY_HDR_TYPE_HLG -> "HLG"
				else -> "Unknown $type"
			}
		}
		.takeIf { it.isNotEmpty() }
		?.joinToString("; ")
		?: "None"

private fun formatDolbyVisionDisplayMode(dolbyVisionModes: String): String =
	when (dolbyVisionModes) {
		"None" -> "DV"
		else -> "DV $dolbyVisionModes"
	}

private fun getDolbyVisionModes(mediaTest: MediaCodecCapabilitiesTest): String =
	formatDolbyVisionModes(
		hevcProfile5 = mediaTest.supportsHevcDolbyVisionProfile5(),
		hevcProfile7 = mediaTest.supportsHevcDolbyVisionProfile7(),
		hevcProfile8 = mediaTest.supportsHevcDolbyVisionProfile8(),
		hevcEnhancementLayer = mediaTest.supportsHevcDolbyVisionEL(),
		av1Profile10 = mediaTest.supportsAV1DolbyVision(),
	)

internal fun formatDolbyVisionModes(
	hevcProfile5: Boolean,
	hevcProfile7: Boolean,
	hevcProfile8: Boolean,
	hevcEnhancementLayer: Boolean,
	av1Profile10: Boolean,
): String = buildList {
	val hevcProfiles = buildList {
		if (hevcProfile5) add("P5")
		if (hevcProfile7) add("P7")
		if (hevcProfile8) add("P8")
		if (hevcEnhancementLayer) add("EL")
	}
	if (hevcProfiles.isNotEmpty()) add("HEVC ${hevcProfiles.joinToString("/")}")
	if (av1Profile10) add("AV1 P10")
}.joinToString(", ").ifBlank { "None" }

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
private fun PlaybackInfoRow(row: PlaybackInfoRowModel) {
	PlaybackInfoStaticRow(row.label, row.value)
}

@Composable
private fun PlaybackInfoStaticRow(
	label: String,
	value: String,
) {
	val style = TextStyle(
		color = Color.White,
		fontSize = 6.sp,
		lineHeight = 7.sp,
		fontFamily = FontFamily.Monospace,
	)

	Row(
		modifier = Modifier.fillMaxWidth(),
	) {
		Text(
			text = "$label: ",
			style = style.copy(fontWeight = FontWeight.W700),
		)
		Text(
			text = value,
			modifier = Modifier.weight(1f),
			style = style,
		)
	}
}

private data class PlaybackInfoSection(
	val title: String,
	val rows: List<PlaybackInfoRowModel>,
)

private data class PlaybackInfoRowModel(
	val label: String,
	val value: String,
)

private fun Duration.formatDuration(): String {
	if (this < Duration.ZERO) return "Unknown"
	val totalSeconds = inWholeSeconds
	val hours = totalSeconds / 3600
	val minutes = (totalSeconds % 3600) / 60
	val seconds = totalSeconds % 60
	return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
	else "%d:%02d".format(minutes, seconds)
}

private fun PositionInfo.formatBuffer(): String {
	val ahead = (buffer - active).coerceAtLeast(Duration.ZERO)
	return "${buffer.formatDuration()} +${ahead.formatDuration()}"
}

private fun buildThumbnailCacheRows(
	trickplayUrls: List<String>,
	chapterUrls: List<String>,
) = listOf(
	PlaybackInfoRowModel(
		label = "Trickplay",
		value = TrickplayTileSheetMemoryCache.stats(trickplayUrls).formatCacheStats(trickplayUrls.size),
	),
	PlaybackInfoRowModel(
		label = "Chapters",
		value = ChapterThumbnailMemoryCache.stats(chapterUrls).formatCacheStats(chapterUrls.size),
	),
)

private fun TrickplayTileSheetMemoryStats.formatCacheStats(total: Int) =
	"$count/$total ${bytes.formatCacheBytes()}"

private fun Long.formatCacheBytes() = "%.1f MiB".format(this / 1024.0 / 1024.0)
