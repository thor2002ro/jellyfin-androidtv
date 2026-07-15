package org.jellyfin.playback.media3.exoplayer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.core.graphics.TypefaceCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilitiesReceiver
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import org.jellyfin.playback.core.backend.BasePlayerBackend
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.PlayerTrack
import org.jellyfin.playback.core.backend.TrackSelectionBackend
import org.jellyfin.playback.core.backend.TrackType
import org.jellyfin.playback.core.mediastream.ExternalSubtitle
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamTrack
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.mediatype.MediaType
import org.jellyfin.playback.core.mediastream.mediatype.mediaType
import org.jellyfin.playback.core.mediastream.normalizationGain
import org.jellyfin.playback.core.model.PlaybackFrameStats
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.liveStreamTargetOffset
import org.jellyfin.playback.core.support.PlaySupportReport
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.ui.PlayerSubtitleStyle
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView
import org.jellyfin.playback.media3.exoplayer.subtitle.SubtitleTimingOffsetRenderersFactory
import org.jellyfin.playback.media3.exoplayer.subtitle.SubtitleTimingOffsetState
import org.jellyfin.playback.media3.exoplayer.subtitle.SubtitleTimingRendererInvalidator
import org.jellyfin.playback.media3.exoplayer.subtitle.isSubtitleTimingAdjustmentSupported
import org.jellyfin.playback.media3.exoplayer.support.getPlaySupportReport
import org.jellyfin.playback.media3.exoplayer.support.toFormats
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class VideoDecoder {
	HARDWARE,
	SOFTWARE,
	FFMPEG,
}

internal fun VideoDecoder?.prefersFfmpeg(defaultPreference: Boolean) =
	this?.let { decoder -> decoder == VideoDecoder.FFMPEG } ?: defaultPreference

internal fun VideoDecoder?.effectiveVideoDecoder(
	stage: VideoDecoder,
	preferFfmpeg: Boolean,
	decoderName: String?,
): VideoDecoder = this ?: when {
	decoderName?.startsWith("ffmpeg", ignoreCase = true) == true -> VideoDecoder.FFMPEG
	decoderName != null -> stage
	preferFfmpeg -> VideoDecoder.FFMPEG
	else -> stage
}

internal fun shouldStartLivePlayback(
	isReady: Boolean,
	bufferedDurationMs: Long,
	targetBufferDurationMs: Long,
	timedOut: Boolean,
) = timedOut || (isReady && bufferedDurationMs >= targetBufferDurationMs)

internal fun hasDecoderStalled(
	queuedInputBufferCount: Int,
	renderedOutputBufferCount: Int,
	currentQueuedInputBufferCount: Int,
	currentRenderedOutputBufferCount: Int,
) = currentQueuedInputBufferCount > queuedInputBufferCount &&
	currentRenderedOutputBufferCount <= renderedOutputBufferCount

internal fun targetLiveTvBufferDuration(
	liveStreamOffset: Duration?,
	configuredOffset: Duration?,
	initialLiveStreamOffset: Duration = 5.seconds,
): Duration? {
	if (liveStreamOffset == null) return null
	if (configuredOffset != null) return maxOf(liveStreamOffset, configuredOffset)
	return liveStreamOffset.takeIf { it > initialLiveStreamOffset }
}

internal fun isAmlogicDevice(fields: Iterable<String?> = amlogicDeviceFields()) =
	fields.any { field -> field?.contains("amlogic", ignoreCase = true) == true }

internal fun liveTvTsExtractorFlags(
	isAmlogic: Boolean,
	container: String?,
	videoCodecs: Iterable<String>,
) = when {
	!isAmlogic -> DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
	isH264TransportStream(container, videoCodecs) -> DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
	else -> 0
}

private fun amlogicDeviceFields() = buildList {
	add(Build.MANUFACTURER)
	add(Build.BRAND)
	add(Build.HARDWARE)
	add(Build.BOARD)
	add(Build.PRODUCT)
	add(Build.DEVICE)
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Build.SOC_MODEL)
	addAll(codecNames())
}

private fun codecNames() = runCatching {
	MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.map { it.name }
}.getOrDefault(emptyList())

private fun isH264TransportStream(container: String?, videoCodecs: Iterable<String>) =
	container.tokens().any { it in transportStreamContainers } &&
		videoCodecs.any(::isH264Codec)

private val transportStreamContainers = setOf("ts", "mpegts", "mpeg-ts", "mpeg_ts")

private fun String?.tokens() = this
	?.lowercase()
	?.split(',', '|', ';', ' ')
	?.map(String::trim)
	?.filter(String::isNotEmpty)
	.orEmpty()

private fun isH264Codec(codec: String) = codec.lowercase().let { value ->
	value.contains("h264") || value.contains("avc")
}

private val currentDeviceIsAmlogic by lazy { isAmlogicDevice() }

@OptIn(UnstableApi::class)
class ExoPlayerBackend(
	private val context: Context,
	private val exoPlayerOptions: ExoPlayerOptions,
) : BasePlayerBackend(), TrackSelectionBackend {
	companion object {
		const val TS_SEARCH_BYTES = 3 * TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
		const val MEDIA_ITEM_COUNT_MAX = 10
		private const val INITIAL_TRACK_SELECTION_RETRY_LIMIT = 8
		private const val INITIAL_TRACK_SELECTION_RETRY_DELAY_MS = 250L
		private const val LIVE_START_CHECK_INTERVAL_MS = 250L
		private const val LIVE_START_TIMEOUT_MS = 15_000L
		private const val VIDEO_FIRST_FRAME_TIMEOUT_MS = 2_000L
		private const val VIDEO_DECODER_STALL_TIMEOUT_MS = 3_000L
		private const val HARDWARE_VIDEO_DECODER_RETRY_LIMIT = 3

		private fun QueueEntry.liveTvTsExtractorFlags(): Int {
			val stream = mediaStream ?: return if (currentDeviceIsAmlogic) 0 else
				DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES

			return liveTvTsExtractorFlags(
				isAmlogic = currentDeviceIsAmlogic,
				container = stream.container.format,
				videoCodecs = stream.tracks.filterIsInstance<MediaStreamVideoTrack>().map { track -> track.codec },
			)
		}

		private fun formatTsExtractorFlags(flags: Int): String {
			if (flags == 0) return "none (0)"
			val names = buildList {
				if (flags and DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES != 0) add("ALLOW_NON_IDR_KEYFRAMES")
				if (flags and DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS != 0) add("DETECT_ACCESS_UNITS")
			}
			return "${names.joinToString("|")} ($flags)"
		}

		private fun Player.playbackStateName(): String = when (playbackState) {
			Player.STATE_IDLE -> "IDLE"
			Player.STATE_BUFFERING -> "BUFFERING"
			Player.STATE_READY -> "READY"
			Player.STATE_ENDED -> "ENDED"
			else -> playbackState.toString()
		}

		private fun MediaItem.safeUriForLogging(): String = localConfiguration
			?.uri
			?.buildUpon()
			?.clearQuery()
			?.fragment(null)
			?.build()
			?.toString()
			.orEmpty()

		private fun String.safeUriForLogging(): String = toUri()
			.buildUpon()
			.clearQuery()
			.fragment(null)
			.build()
			.toString()

		private fun PlaybackException.canRecoverLiveTvWithIncreasedOffset(): Boolean = when (errorCode) {
			PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
			PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
			PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
			PlaybackException.ERROR_CODE_DECODING_FAILED -> true

			else -> false
		}
	}

	private var currentStream: PlayableMediaStream? = null
	private var playerSurfaceView: PlayerSurfaceView? = null
	private var subtitleSurfaceView: PlayerSubtitleView? = null
	private var subtitleView: SubtitleView? = null
	private val audioPipeline = ExoPlayerAudioPipeline()
	private val audioAttributeState = AudioAttributeState()
	private val timedEventState = TimedEventState()
	private var lastKnownDuration: Duration? = null
	private val subtitleTimingOffsetState = SubtitleTimingOffsetState()
	private val subtitleTimingRendererInvalidator by lazy {
		SubtitleTimingRendererInvalidator(
			state = subtitleTimingOffsetState,
			playerProvider = { if (exoPlayerDelegate.isInitialized()) exoPlayer else null },
			refreshSupported = {
				exoPlayerDelegate.isInitialized() && canAdjustSubtitleTiming(exoPlayer.currentTracks)
			},
		)
	}
	private val startHandler = Handler(Looper.getMainLooper())
	private val initialTrackSelectionHandler = Handler(Looper.getMainLooper())
	private val videoFirstFrameHandler = Handler(Looper.getMainLooper())
	private val videoBufferingHandler = Handler(Looper.getMainLooper())
	private val videoRendererSwitchHandler = Handler(Looper.getMainLooper())
	private var pendingLiveStartStream: PlayableMediaStream? = null
	private var pendingInitialTrackSelection: PendingInitialTrackSelection? = null
	private var pendingInitialTrackSelectionRetryCount = 0
	private var videoDecoderName: String? = null
	private var videoDecoderType: String? = null
	private var videoInputFormat: Format? = null
	private var videoDecoderCounters: DecoderCounters? = null
	private var hasRenderedFirstVideoFrame = false
	private var videoDecoderStage = VideoDecoder.HARDWARE
	private var hardwareVideoDecoderRetryCount = 0
	private var disabledHardwareVideoRendererIndex: Int? = null
	var forcedVideoDecoder: VideoDecoder? = null
		private set
	val activeVideoDecoder: VideoDecoder
		get() = forcedVideoDecoder.effectiveVideoDecoder(
			stage = videoDecoderStage,
			preferFfmpeg = exoPlayerOptions.preferFfmpegVideo(),
			decoderName = videoDecoderName,
		)
	private var audioDecoderName: String? = null
	private var audioDecoderType: String? = null
	private var audioDecoderCounters: DecoderCounters? = null
	private var audioInputFormat: Format? = null
	private var audioPassthroughSupported: Boolean? = null
	private var audioPassthroughSupportDirty = true
	private var audioCapabilities: AudioCapabilities? = null
	private var audioCapabilitiesReceiverRegistered = false
	private var audioCapabilitiesReceiverFailed = false
	private var reportedPlayState: PlayState? = null
	private var reportedEndedStream: PlayableMediaStream? = null
	private var rendererPreferencesDirty = false
	private lateinit var mediaSourceFactory: MediaSource.Factory
	private val audioCapabilitiesReceiver by lazy {
		AudioCapabilitiesReceiver(
			context,
			AudioCapabilitiesReceiver.Listener { capabilities ->
				audioCapabilities = capabilities
				refreshAudioPassthroughSupport(
					capabilities = capabilities,
					allowReceiverRegistration = false,
				)
			},
			audioAttributeState.audioAttributes ?: AudioAttributes.DEFAULT,
			null,
		)
	}

	private fun currentMediaItemMatchesCurrentStream(): Boolean {
		val mediaId = currentStream?.hashCode()?.toString() ?: return false
		return exoPlayer.currentMediaItem?.mediaId == mediaId
	}

	private fun getSafePositionInfo(): PositionInfo {
		val activePositionMs = exoPlayer.currentPosition
		val bufferedPositionMs = exoPlayer.bufferedPosition
		val mediaItemMatchesCurrentStream = currentMediaItemMatchesCurrentStream()
		val duration = if (mediaItemMatchesCurrentStream) lastKnownDuration ?: Duration.ZERO else Duration.ZERO
		val safeBufferedPositionMs = when {
			!mediaItemMatchesCurrentStream -> activePositionMs
			bufferedPositionMs < activePositionMs -> activePositionMs
			duration > Duration.ZERO && bufferedPositionMs > duration.inWholeMilliseconds -> duration.inWholeMilliseconds
			else -> bufferedPositionMs
		}

		return PositionInfo(
			active = activePositionMs.milliseconds,
			buffer = safeBufferedPositionMs.milliseconds,
			duration = duration,
		)
	}

	private fun reportCurrentMediaStreamEnd(reason: String) {
		val stream = currentStream ?: return
		if (reportedEndedStream == stream) return

		reportedEndedStream = stream
		val positionInfo = getSafePositionInfo()
		Timber.i(
			"ExoPlayer media stream ended reason=%s mediaId=%s playbackState=%s positionMs=%s durationMs=%s",
			reason,
			stream.hashCode().toString(),
			exoPlayer.playbackStateName(),
			positionInfo.active.inWholeMilliseconds,
			positionInfo.duration.inWholeMilliseconds,
		)
		listener?.onMediaStreamEnd(stream)
	}

	private fun clearSubtitleCues() {
		subtitleView?.setCues(emptyList())
	}

	private fun setPendingInitialTrackSelection(selection: PendingInitialTrackSelection?) {
		initialTrackSelectionHandler.removeCallbacksAndMessages(null)
		pendingInitialTrackSelectionRetryCount = 0
		pendingInitialTrackSelection = selection
	}

	private fun clearPendingLiveStart() {
		startHandler.removeCallbacksAndMessages(null)
		pendingLiveStartStream = null
	}

	private fun clearPendingInitialTrackSelection() {
		setPendingInitialTrackSelection(null)
	}

	private fun schedulePendingInitialTrackSelectionRetry() {
		if (pendingInitialTrackSelection == null) return
		if (pendingInitialTrackSelectionRetryCount >= INITIAL_TRACK_SELECTION_RETRY_LIMIT) return

		initialTrackSelectionHandler.removeCallbacksAndMessages(null)
		initialTrackSelectionHandler.postDelayed(
			{ retryPendingInitialTrackSelection() },
			INITIAL_TRACK_SELECTION_RETRY_DELAY_MS,
		)
	}

	private fun retryPendingInitialTrackSelection() {
		val pending = pendingInitialTrackSelection ?: return

		applyPendingInitialTrackSelection()
		if (pendingInitialTrackSelection == null) return

		pendingInitialTrackSelectionRetryCount++
		if (pendingInitialTrackSelectionRetryCount >= INITIAL_TRACK_SELECTION_RETRY_LIMIT) {
			Timber.w(
				"Initial track selection still pending after %d retries mediaId=%s audioStreamIndex=%s subtitleStreamIndex=%s",
				pendingInitialTrackSelectionRetryCount,
				currentStream?.hashCode()?.toString() ?: "none",
				pending.audioStreamIndex?.toString() ?: "none",
				pending.subtitleStreamIndex?.toString() ?: "none",
			)
			return
		}

		schedulePendingInitialTrackSelectionRetry()
	}

	private fun QueueEntry.liveTvBufferDuration(): Duration? {
		return targetLiveTvBufferDuration(liveStreamTargetOffset, exoPlayerOptions.liveTvBufferDuration)
	}

	private fun resetPlaybackStats() {
		resetVideoDecoderFallback()
		videoDecoderName = null
		videoDecoderType = null
		videoInputFormat = null
		videoDecoderCounters = null
		audioDecoderName = null
		audioDecoderType = null
		audioDecoderCounters = null
		audioInputFormat = null
		audioPassthroughSupported = null
		audioPassthroughSupportDirty = true
		audioCapabilitiesReceiverFailed = false
	}

	private fun resetVideoDecoderFallback() {
		videoFirstFrameHandler.removeCallbacksAndMessages(null)
		videoBufferingHandler.removeCallbacksAndMessages(null)
		videoRendererSwitchHandler.removeCallbacksAndMessages(null)
		hasRenderedFirstVideoFrame = false
		videoDecoderStage = VideoDecoder.HARDWARE
		hardwareVideoDecoderRetryCount = 0
		disabledHardwareVideoRendererIndex?.let { rendererIndex ->
			trackSelector.setParameters(
				trackSelector.buildUponParameters().setRendererDisabled(rendererIndex, false)
			)
		}
		disabledHardwareVideoRendererIndex = null
	}

	private fun scheduleVideoFirstFrameFallback(decoderName: String) {
		if (forcedVideoDecoder != null || exoPlayerOptions.preferFfmpegVideo() || decoderName.startsWith("ffmpeg", ignoreCase = true)) return

		val mediaId = exoPlayer.currentMediaItem?.mediaId ?: return
		val counters = videoDecoderCounters ?: return
		val queuedInputBufferCount = counters.queuedInputBufferCount
		val renderedOutputBufferCount = counters.renderedOutputBufferCount
		videoFirstFrameHandler.removeCallbacksAndMessages(null)
		videoFirstFrameHandler.postDelayed({
			if (videoDecoderName != decoderName || exoPlayer.currentMediaItem?.mediaId != mediaId) {
				return@postDelayed
			}
			if (!hasDecoderStalled(
				queuedInputBufferCount,
				renderedOutputBufferCount,
				counters.queuedInputBufferCount,
				counters.renderedOutputBufferCount,
			)) return@postDelayed

			fallbackVideoDecoder(decoderName, counters, "rendered no first frame after ${VIDEO_FIRST_FRAME_TIMEOUT_MS}ms")
		}, VIDEO_FIRST_FRAME_TIMEOUT_MS)
	}

	private fun scheduleLiveTvDecoderStallFallback() {
		videoBufferingHandler.removeCallbacksAndMessages(null)
		if (!hasRenderedFirstVideoFrame || currentStream?.queueEntry?.liveStreamTargetOffset == null) return

		val decoderName = videoDecoderName ?: return
		if (forcedVideoDecoder != null || exoPlayerOptions.preferFfmpegVideo() || decoderName.startsWith("ffmpeg", ignoreCase = true)) return
		val mediaId = exoPlayer.currentMediaItem?.mediaId ?: return
		val counters = videoDecoderCounters ?: return
		val queuedInputBufferCount = counters.queuedInputBufferCount
		val renderedOutputBufferCount = counters.renderedOutputBufferCount

		videoBufferingHandler.postDelayed({
			if (exoPlayer.playbackState != Player.STATE_BUFFERING || !exoPlayer.playWhenReady) return@postDelayed
			if (videoDecoderName != decoderName || exoPlayer.currentMediaItem?.mediaId != mediaId) return@postDelayed
			if (!hasDecoderStalled(
				queuedInputBufferCount,
				renderedOutputBufferCount,
				counters.queuedInputBufferCount,
				counters.renderedOutputBufferCount,
			)) return@postDelayed

			fallbackVideoDecoder(decoderName, counters, "decoder accepted input without rendering output for ${VIDEO_DECODER_STALL_TIMEOUT_MS}ms")
		}, VIDEO_DECODER_STALL_TIMEOUT_MS)
	}

	private fun fallbackVideoDecoder(decoderName: String, counters: DecoderCounters, reason: String) {
		if (videoDecoderName != decoderName || forcedVideoDecoder != null || exoPlayerOptions.preferFfmpegVideo()) return

		val mediaId = exoPlayer.currentMediaItem?.mediaId ?: return
		val hardwareRendererIndex = (0 until exoPlayer.rendererCount)
			.firstOrNull { exoPlayer.getRendererType(it) == C.TRACK_TYPE_VIDEO }
			?: return
		val retryHardware = videoDecoderStage == VideoDecoder.HARDWARE &&
			hardwareVideoDecoderRetryCount < HARDWARE_VIDEO_DECODER_RETRY_LIMIT
		val nextStage = when {
			retryHardware -> VideoDecoder.HARDWARE
			videoDecoderStage == VideoDecoder.HARDWARE -> VideoDecoder.SOFTWARE
			else -> VideoDecoder.FFMPEG
		}
		if (retryHardware) hardwareVideoDecoderRetryCount++
		videoDecoderStage = nextStage
		Timber.w(
			"Video decoder %s; resetting decoder=%s target=%s mediaId=%s queuedInputs=%d hardwareRetry=%d/%d",
			reason,
			decoderName,
			when (nextStage) {
				VideoDecoder.HARDWARE -> "hardware"
				VideoDecoder.SOFTWARE -> "Android software"
				else -> "FFmpeg"
			},
			mediaId,
			counters.queuedInputBufferCount,
			hardwareVideoDecoderRetryCount,
			HARDWARE_VIDEO_DECODER_RETRY_LIMIT,
		)
		trackSelector.setParameters(
			trackSelector.buildUponParameters().setRendererDisabled(hardwareRendererIndex, true)
		)
		if (nextStage != VideoDecoder.FFMPEG) {
			videoRendererSwitchHandler.post {
				trackSelector.setParameters(
					trackSelector.buildUponParameters().setRendererDisabled(hardwareRendererIndex, false)
				)
			}
		} else {
			disabledHardwareVideoRendererIndex = hardwareRendererIndex
		}
	}

	private val mediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
		val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
		if (!mimeType.startsWith("video/")) decoders else when (forcedVideoDecoder ?: videoDecoderStage) {
			VideoDecoder.HARDWARE -> decoders.filterNot { it.softwareOnly }
			VideoDecoder.SOFTWARE -> decoders.filter { it.softwareOnly }
			else -> emptyList()
		}
	}

	private fun unregisterAudioCapabilitiesReceiver() {
		if (!audioCapabilitiesReceiverRegistered) return

		runCatching { audioCapabilitiesReceiver.unregister() }
			.onFailure { error -> Timber.w(error, "Failed to unregister audio capabilities receiver") }
		audioCapabilitiesReceiverRegistered = false
		audioCapabilities = null
		audioPassthroughSupportDirty = true
	}

	private fun QueueEntry.toMediaItem(stream: PlayableMediaStream): MediaItem = MediaItem.Builder().apply {
		setTag(this@toMediaItem)
		setMediaId(stream.hashCode().toString())
		setUri(stream.url)
		liveTvBufferDuration()?.let { offset ->
			setLiveConfiguration(
				MediaItem.LiveConfiguration.Builder()
					.setTargetOffsetMs(offset.inWholeMilliseconds)
					.build()
			)
		}

		if (stream.externalSubtitles.isNotEmpty()) {
			setSubtitleConfigurations(stream.externalSubtitles.map { sub ->
				MediaItem.SubtitleConfiguration.Builder(sub.url.toUri())
					.setId("external:${sub.index}")
					.setMimeType(sub.mimeType)
					.setLanguage(sub.language)
					.setLabel(sub.title)
					.setSelectionFlags(sub.selectionFlags)
					.build()
			})
		}
	}.build()

	private fun QueueEntry.createMediaSource(stream: PlayableMediaStream): MediaSource {
		val mediaItem = toMediaItem(stream)
		Timber.i(
			"Creating explicit media source mediaId=%s isLiveTv=%s uri=%s",
			mediaItem.mediaId,
			liveStreamTargetOffset != null,
			mediaItem.safeUriForLogging(),
		)
		return mediaSourceFactory.createMediaSource(mediaItem)
	}

	private val assHandler by lazy {
		AssHandler(
			exoPlayerOptions.libassRenderType,
			AssHandlerConfig(
				glyphSize = exoPlayerOptions.libassGlyphSize,
				cacheSize = exoPlayerOptions.libassCacheSize,
				maxRenderPixels = exoPlayerOptions.libassMaxRenderPixels,
			),
		)
	}

	private val shouldUseLibassOverlayView: Boolean
		get() = exoPlayerOptions.enableLibass && when (exoPlayerOptions.libassRenderType) {
			AssRenderType.OVERLAY_CANVAS,
			AssRenderType.OVERLAY_OPEN_GL -> true
			else -> false
		}

	private fun createTrackSelector() =
		DefaultTrackSelector(context).apply {
			setParameters(buildUponParameters().apply {
				setAudioOffloadPreferences(
					TrackSelectionParameters.AudioOffloadPreferences.DEFAULT.buildUpon().apply {
						setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
					}.build()
				)
				setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
			})
		}

	private var trackSelectorDelegate = lazy(::createTrackSelector)
	private val trackSelector get() = trackSelectorDelegate.value

	private var exoPlayerDelegate = lazy(::createExoPlayer)
	private val exoPlayer get() = exoPlayerDelegate.value

	private fun createExoPlayer(): ExoPlayer {
		val dataSourceFactory = DefaultDataSource.Factory(
			context,
			exoPlayerOptions.baseDataSourceFactory,
		)

		fun createExtractorsFactory(tsExtractorFlags: Int = 0) = DefaultExtractorsFactory().apply {
			setTsExtractorTimestampSearchBytes(TS_SEARCH_BYTES)
			if (tsExtractorFlags != 0) setTsExtractorFlags(tsExtractorFlags)
			setConstantBitrateSeekingEnabled(true)
			setConstantBitrateSeekingAlwaysEnabled(true)
		}

		fun DefaultMediaSourceFactory.configureSubtitles(subtitleParserFactory: SubtitleParser.Factory) = apply {
			// Timing offsets are applied by SubtitleTimingOffsetDecoderFactory in the renderer.
			// Extraction-time parsing emits pre-timed Media3 cue samples and bypasses that decoder.
			@Suppress("DEPRECATION")
			experimentalParseSubtitlesDuringExtraction(
				exoPlayerOptions.parseSubtitlesDuringExtraction && !exoPlayerOptions.enableLibass
			)
			setSubtitleParserFactory(subtitleParserFactory)
		}

		fun createMediaSourceFactory(
			extractorsFactory: DefaultExtractorsFactory,
			subtitleParserFactory: SubtitleParser.Factory,
			assSubtitleParserFactory: AssSubtitleParserFactory? = null,
		): DefaultMediaSourceFactory {
			val extractors: ExtractorsFactory = assSubtitleParserFactory
				?.let { extractorsFactory.withAssMkvSupport(it, assHandler) }
				?: extractorsFactory
			return DefaultMediaSourceFactory(dataSourceFactory, extractors)
				.configureSubtitles(subtitleParserFactory)
		}

		fun MediaSource.Factory.withExternalSubtitlesInRenderer() =
			ExternalSubtitleMediaSourceFactory(this, dataSourceFactory)

		fun RenderersFactory.withFfmpegPreferences() = RenderersFactory {
				eventHandler,
				videoRendererEventListener,
				audioRendererEventListener,
				textRendererOutput,
				metadataRendererOutput,
			->
			val renderers = createRenderers(
				eventHandler,
				videoRendererEventListener,
				audioRendererEventListener,
				textRendererOutput,
				metadataRendererOutput,
			).toMutableList()

			fun preferFfmpeg(trackType: Int) {
				val firstRendererIndex = renderers.indexOfFirst { it.trackType == trackType }
				val ffmpegRendererIndex = renderers.indexOfFirst {
					when (trackType) {
						C.TRACK_TYPE_AUDIO -> it is FfmpegAudioRenderer
						C.TRACK_TYPE_VIDEO -> it is ExperimentalFfmpegVideoRenderer
						else -> false
					}
				}
				if (firstRendererIndex >= 0 && ffmpegRendererIndex > firstRendererIndex) {
					renderers.add(firstRendererIndex, renderers.removeAt(ffmpegRendererIndex))
				}
			}

			if (forcedVideoDecoder != null && forcedVideoDecoder != VideoDecoder.FFMPEG) {
				renderers.removeAll { renderer -> renderer is ExperimentalFfmpegVideoRenderer }
			}
			if (forcedVideoDecoder.prefersFfmpeg(exoPlayerOptions.preferFfmpegVideo())) preferFfmpeg(C.TRACK_TYPE_VIDEO)
			if (exoPlayerOptions.preferFfmpegAudio()) preferFfmpeg(C.TRACK_TYPE_AUDIO)
			renderers.toTypedArray()
		}

		val normalExtractorsFactory = createExtractorsFactory()
		val liveTvExtractorsFactory = createExtractorsFactory(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
		val amlogicH264LiveTvExtractorsFactory = createExtractorsFactory(DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)

		fun createLiveTvMediaSourceFactory(
			subtitleParserFactory: SubtitleParser.Factory,
			assSubtitleParserFactory: AssSubtitleParserFactory? = null,
		) = LiveTvMediaSourceFactory(
			defaultFactory = createMediaSourceFactory(
				normalExtractorsFactory,
				subtitleParserFactory,
				assSubtitleParserFactory,
			),
			liveTvFactory = createMediaSourceFactory(
				liveTvExtractorsFactory,
				subtitleParserFactory,
				assSubtitleParserFactory,
			),
			amlogicH264LiveTvFactory = createMediaSourceFactory(
				amlogicH264LiveTvExtractorsFactory,
				subtitleParserFactory,
				assSubtitleParserFactory,
			),
		)

		val renderersFactory: RenderersFactory
		if (exoPlayerOptions.enableLibass) {
			val assSubtitleParserFactory = AssSubtitleParserFactory(assHandler)
			val subtitleParserFactory = when (exoPlayerOptions.libassRenderType) {
				AssRenderType.CUES -> DefaultSubtitleParserFactory()
				else -> assSubtitleParserFactory
			}
			mediaSourceFactory = createLiveTvMediaSourceFactory(subtitleParserFactory, assSubtitleParserFactory)
				.withExternalSubtitlesInRenderer()
			renderersFactory = SubtitleTimingOffsetRenderersFactory(
				context = context,
				offsetState = subtitleTimingOffsetState,
				subtitleParserFactory = subtitleParserFactory,
			).apply {
				setEnableDecoderFallback(true)
				setMediaCodecSelector(mediaCodecSelector)
				setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
			}.let { AssRenderersFactory(assHandler, it) }
		} else {
			val defaultSubtitleParserFactory = DefaultSubtitleParserFactory()
			mediaSourceFactory = createLiveTvMediaSourceFactory(defaultSubtitleParserFactory)
				.withExternalSubtitlesInRenderer()
			renderersFactory = SubtitleTimingOffsetRenderersFactory(
				context = context,
				offsetState = subtitleTimingOffsetState,
				subtitleParserFactory = defaultSubtitleParserFactory,
			).apply {
				setEnableDecoderFallback(true)
				setMediaCodecSelector(mediaCodecSelector)
				setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
			}
		}

		val loadControl = DefaultLoadControl.Builder()
			.setBufferDurationsMs(
				exoPlayerOptions.minBufferDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
				exoPlayerOptions.maxBufferDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
				exoPlayerOptions.bufferForPlaybackDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
				exoPlayerOptions.bufferForPlaybackAfterRebufferDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
			)
			.setPrioritizeTimeOverSizeThresholds(true)
			.build()

		return ExoPlayer.Builder(context)
			.setLoadControl(loadControl)
			.setRenderersFactory(renderersFactory.withFfmpegPreferences())
			.setTrackSelector(trackSelector)
			.setMediaSourceFactory(mediaSourceFactory)
			.setPauseAtEndOfMediaItems(true)
			.build()
			.also { player ->
				subtitleTimingRendererInvalidator
				player.setVideoSurfaceView(playerSurfaceView?.surface)
				player.addListener(PlayerListener())
				player.addAnalyticsListener(DecoderAnalyticsListener())

				if (exoPlayerOptions.enableDebugLogging) {
					player.addAnalyticsListener(EventLogger())
				}

				if (exoPlayerOptions.enableLibass) {
					assHandler.init(player)
				}
			}
	}

	fun invalidateRendererPreferences() {
		rendererPreferencesDirty = true
		applyRendererPreferences()
		exoPlayerDelegate.value
	}

	fun setForcedVideoDecoder(decoder: VideoDecoder?) {
		if (forcedVideoDecoder == decoder) return
		forcedVideoDecoder = decoder
		rendererPreferencesDirty = true
	}

	private fun applyRendererPreferences() {
		if (!rendererPreferencesDirty) return
		if (exoPlayerDelegate.isInitialized()) {
			subtitleTimingRendererInvalidator.cancel()
			exoPlayer.release()
		}
		trackSelectorDelegate = lazy(::createTrackSelector)
		exoPlayerDelegate = lazy(::createExoPlayer)
		rendererPreferencesDirty = false
	}

	private fun canAdjustSubtitleTiming(tracks: Tracks): Boolean {
		val stream = currentStream ?: return false
		val isLiveTv = stream.queueEntry.liveStreamTargetOffset != null
		val subtitlesParsedDuringExtraction =
			exoPlayerOptions.parseSubtitlesDuringExtraction && !exoPlayerOptions.enableLibass
		return tracks.groups.any { group ->
			group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { trackIndex ->
				group.isTrackSelected(trackIndex) && isSubtitleTimingAdjustmentSupported(
					format = group.getTrackFormat(trackIndex),
					isExternal = group.mediaTrackGroup.isExternalSubtitleTrack(trackIndex),
					isLiveTv = isLiveTv,
					subtitlesParsedDuringExtraction = subtitlesParsedDuringExtraction,
					usesLibassOverlay = shouldUseLibassOverlayView,
				)
			}
		}
	}

	inner class DecoderAnalyticsListener : AnalyticsListener {
		override fun onAudioEnabled(
			eventTime: AnalyticsListener.EventTime,
			decoderCounters: DecoderCounters,
		) {
			audioDecoderCounters = decoderCounters
		}

		override fun onVideoEnabled(
			eventTime: AnalyticsListener.EventTime,
			decoderCounters: DecoderCounters,
		) {
			videoDecoderCounters = decoderCounters
		}

		override fun onVideoDecoderInitialized(
			eventTime: AnalyticsListener.EventTime,
			decoderName: String,
			initializedTimestampMs: Long,
			initializationDurationMs: Long,
		) {
			hasRenderedFirstVideoFrame = false
			videoDecoderName = decoderName
			videoDecoderType = when {
				decoderName.startsWith("ffmpeg", ignoreCase = true) -> "ffmpeg"
				(forcedVideoDecoder ?: videoDecoderStage) == VideoDecoder.SOFTWARE -> "sw"
				else -> "hw"
			}
			scheduleVideoFirstFrameFallback(decoderName)
		}

		override fun onRenderedFirstFrame(
			eventTime: AnalyticsListener.EventTime,
			output: Any,
			renderTimeMs: Long,
		) {
			hasRenderedFirstVideoFrame = true
			videoFirstFrameHandler.removeCallbacksAndMessages(null)
		}

		override fun onVideoDecoderReleased(
			eventTime: AnalyticsListener.EventTime,
			decoderName: String,
		) {
			videoFirstFrameHandler.removeCallbacksAndMessages(null)
			videoBufferingHandler.removeCallbacksAndMessages(null)
			if (videoDecoderName == decoderName) {
				videoDecoderName = null
				videoDecoderType = null
			}
		}

		override fun onVideoInputFormatChanged(
			eventTime: AnalyticsListener.EventTime,
			format: Format,
			decoderReuseEvaluation: DecoderReuseEvaluation?,
		) {
			videoInputFormat = format
			videoDecoderName?.let(::scheduleVideoFirstFrameFallback)
		}

		override fun onVideoDisabled(
			eventTime: AnalyticsListener.EventTime,
			decoderCounters: DecoderCounters,
		) {
			videoFirstFrameHandler.removeCallbacksAndMessages(null)
			videoBufferingHandler.removeCallbacksAndMessages(null)
			if (videoDecoderCounters === decoderCounters) {
				videoDecoderName = null
				videoDecoderType = null
				videoInputFormat = null
				videoDecoderCounters = null
			}
		}

		override fun onAudioDecoderInitialized(
			eventTime: AnalyticsListener.EventTime,
			decoderName: String,
			initializedTimestampMs: Long,
			initializationDurationMs: Long,
		) {
			audioDecoderName = decoderName
			audioDecoderType = decoderType(decoderName, audioInputFormat)
		}

		override fun onAudioDecoderReleased(
			eventTime: AnalyticsListener.EventTime,
			decoderName: String,
		) {
			if (audioDecoderName == decoderName) {
				audioDecoderName = null
				audioDecoderType = null
			}
		}

		override fun onAudioInputFormatChanged(
			eventTime: AnalyticsListener.EventTime,
			format: Format,
			decoderReuseEvaluation: DecoderReuseEvaluation?,
		) {
			audioInputFormat = format
			audioDecoderName?.let { audioDecoderType = decoderType(it, format) }
			audioPassthroughSupported = null
			audioPassthroughSupportDirty = true
			audioCapabilitiesReceiverFailed = false
			refreshAudioPassthroughSupport(
				format = format,
				allowReceiverRegistration = false,
			)
		}

		override fun onAudioDisabled(
			eventTime: AnalyticsListener.EventTime,
			decoderCounters: DecoderCounters,
		) {
			if (audioDecoderCounters === decoderCounters) {
				audioDecoderName = null
				audioDecoderType = null
				audioDecoderCounters = null
				audioInputFormat = null
				audioPassthroughSupported = null
				audioPassthroughSupportDirty = true
				audioCapabilitiesReceiverFailed = false
				unregisterAudioCapabilitiesReceiver()
			}
		}
	}

	private fun decoderType(decoderName: String, format: Format?): String {
		if (decoderName.startsWith("ffmpeg", ignoreCase = true)) return "ffmpeg"

		val mimeType = format?.sampleMimeType ?: return "hw"
		val decoderInfo = runCatching {
			MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, false, false)
		}.getOrNull()?.firstOrNull { info -> info.name == decoderName }
		return if (decoderInfo?.softwareOnly == true) "sw" else "hw"
	}

	inner class PlayerListener : Player.Listener {
		override fun onIsPlayingChanged(isPlaying: Boolean) {
			val state = when {
				isPlaying -> PlayState.PLAYING
				pendingLiveStartStream != null && pendingLiveStartStream == currentStream -> PlayState.BUFFERING
				exoPlayer.playWhenReady && exoPlayer.playbackState == Player.STATE_BUFFERING -> PlayState.BUFFERING
				exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playbackState == Player.STATE_ENDED -> PlayState.STOPPED
				else -> PlayState.PAUSED
			}
			if (state != reportedPlayState) {
				val positionInfo = getSafePositionInfo()
				Timber.i(
					"ExoPlayer play state changed mappedState=%s playbackState=%s playWhenReady=%s positionMs=%s bufferedMs=%s mediaId=%s",
					state,
					exoPlayer.playbackStateName(),
					exoPlayer.playWhenReady,
					positionInfo.active.inWholeMilliseconds,
					positionInfo.buffer.inWholeMilliseconds,
					exoPlayer.currentMediaItem?.mediaId,
				)
				reportedPlayState = state
			}
			listener?.onPlayStateChange(state)
		}

		override fun onPlayerError(error: PlaybackException) {
			val isLiveTv = (exoPlayer.currentMediaItem?.localConfiguration?.tag as? QueueEntry)
				?.liveStreamTargetOffset != null
			val playbackError = PlaybackError(
				codeName = error.errorCodeName,
				recoverWithIncreasedLiveTvOffset = isLiveTv && error.canRecoverLiveTvWithIncreasedOffset(),
			)
			Timber.e(
				error,
				"ExoPlayer error code=%s mediaId=%s uri=%s method=%s recoverWithIncreasedLiveTvOffset=%s",
				error.errorCodeName,
				exoPlayer.currentMediaItem?.mediaId,
				exoPlayer.currentMediaItem?.safeUriForLogging(),
				currentStream?.conversionMethod,
				playbackError.recoverWithIncreasedLiveTvOffset,
			)
			listener?.onPlaybackError(playbackError)
			listener?.onPlayStateChange(PlayState.ERROR)
		}

		override fun onVideoSizeChanged(size: VideoSize) {
			if (size != VideoSize.UNKNOWN) {
				listener?.onVideoSizeChange(size.width, size.height)
			}
		}

		override fun onCues(cueGroup: CueGroup) {
			subtitleView?.setCues(cueGroup.cues)
		}

		override fun onPlaybackStateChanged(playbackState: Int) {
			if (playbackState == Player.STATE_BUFFERING) scheduleLiveTvDecoderStallFallback()
			else videoBufferingHandler.removeCallbacksAndMessages(null)
			onIsPlayingChanged(exoPlayer.isPlaying)
			if (playbackState == Player.STATE_ENDED) {
				reportCurrentMediaStreamEnd("playback-state-ended")
			}
		}

		override fun onTracksChanged(tracks: Tracks) {
			applyPendingInitialTrackSelection()
			schedulePendingInitialTrackSelectionRetry()

			listener?.onSubtitleTimingOffsetSupportChange(
				supported = canAdjustSubtitleTiming(tracks),
				resetTimingOnUnsupported = currentStream != null,
			)
		}

		override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
			if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
				reportCurrentMediaStreamEnd("play-when-ready-end-of-media-item")
			}
			onIsPlayingChanged(exoPlayer.isPlaying)
		}

		override fun onAudioSessionIdChanged(audioSessionId: Int) {
			audioPipeline.setAudioSessionId(audioSessionId)
		}

		override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
			val queueEntry = mediaItem?.localConfiguration?.tag as? QueueEntry
			audioPipeline.normalizationGain = queueEntry?.normalizationGain
			schedulePendingInitialTrackSelectionRetry()
		}

		override fun onTimelineChanged(timeline: Timeline, reason: Int) {
			val duration = exoPlayer.duration.takeUnless { it == C.TIME_UNSET }?.milliseconds
			if (duration == lastKnownDuration) return
			timedEventState.onDurationChange(exoPlayer, duration)
			lastKnownDuration = duration
		}

		override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
			timedEventState.onSeek(oldPosition.positionMs.milliseconds, newPosition.positionMs.milliseconds, lastKnownDuration ?: Duration.ZERO)
		}
	}

	override fun supportsStream(
		stream: MediaStream
	): PlaySupportReport = exoPlayer.getPlaySupportReport(stream.toFormats())

	override fun setSurfaceView(surfaceView: PlayerSurfaceView?) {
		playerSurfaceView = surfaceView
		exoPlayer.setVideoSurfaceView(surfaceView?.surface)
	}

	override fun setSubtitleView(surfaceView: PlayerSubtitleView?) {
		subtitleSurfaceView?.onSubtitleStyleChanged = null
		subtitleSurfaceView = surfaceView

		if (surfaceView != null) {
			if (subtitleView == null) {
				subtitleView = SubtitleView(surfaceView.context).apply {
					if (shouldUseLibassOverlayView) {
						addView(AssSubtitleView(surfaceView.context, assHandler))
					}
				}
			}

			subtitleView?.applySubtitleStyle(surfaceView.subtitleStyle)
			surfaceView.onSubtitleStyleChanged = { subtitleStyle ->
				subtitleView?.applySubtitleStyle(subtitleStyle)
			}
			surfaceView.addView(subtitleView)
		} else {
			(subtitleView?.parent as? ViewGroup)?.removeView(subtitleView)
			subtitleView = null
		}
	}

	private fun SubtitleView.applySubtitleStyle(subtitleStyle: PlayerSubtitleStyle) {
		val edgeType = if (Color.alpha(subtitleStyle.edgeColor) == 0) {
			CaptionStyleCompat.EDGE_TYPE_NONE
		} else {
			CaptionStyleCompat.EDGE_TYPE_OUTLINE
		}

		setFixedTextSize(TypedValue.COMPLEX_UNIT_DIP, subtitleStyle.textSizeDp)
		setBottomPaddingFraction(subtitleStyle.bottomPaddingFraction)
		setStyle(
			CaptionStyleCompat(
				subtitleStyle.textColor,
				subtitleStyle.backgroundColor,
				Color.TRANSPARENT,
				edgeType,
				subtitleStyle.edgeColor,
				TypefaceCompat.create(context, Typeface.DEFAULT, subtitleStyle.textWeight, false),
			)
		)
	}

	override fun prepareItem(item: QueueEntry) {
		applyRendererPreferences()
		val stream = requireNotNull(item.mediaStream)
		val player = exoPlayer

		// Remove any excessive items from the start
		while (player.mediaItemCount > MEDIA_ITEM_COUNT_MAX - 1) player.removeMediaItem(0)

		if (item.liveStreamTargetOffset != null) {
			// Add Live TV as a MediaSource so bitrate reloads cannot reuse an old source.
			player.addMediaSource(item.createMediaSource(stream))
		} else {
			// Add new item to the end of the media item list
			player.addMediaItem(item.toMediaItem(stream))
		}

		// Instruct exoplayer to prepare
		player.prepare()
	}

	override fun playItem(item: QueueEntry) = playItem(item, delayLiveStart = true)

	private fun playItem(
		item: QueueEntry,
		delayLiveStart: Boolean,
	) {
		val stream = requireNotNull(item.mediaStream)
		if (currentStream == stream) return

		clearSubtitleCues()
		currentStream = null
		listener?.onSubtitleTimingOffsetSupportChange(false, resetTimingOnUnsupported = false)
		currentStream = stream
		reportedEndedStream = null
		resetPlaybackStats()
		setPendingInitialTrackSelection(stream.initialTrackSelection())

		var preparedItemIndex = (0 until exoPlayer.mediaItemCount).firstOrNull { index ->
			exoPlayer.getMediaItemAt(index).mediaId == stream.hashCode().toString()
		}

		// Prepare the item now if it doesn't exist yet
		if (preparedItemIndex == null) {
			prepareItem(item)
			preparedItemIndex = exoPlayer.mediaItemCount - 1
		}

		// Seek to prepared media item
		when (preparedItemIndex) {
			exoPlayer.currentMediaItemIndex - 1 -> exoPlayer.seekToPreviousMediaItem()
			exoPlayer.currentMediaItemIndex + 1 -> exoPlayer.seekToNextMediaItem()
			exoPlayer.currentMediaItemIndex -> Unit
			else -> exoPlayer.seekTo(preparedItemIndex, 0)
		}
		schedulePendingInitialTrackSelectionRetry()

		// Update audio attributes
		val contentType = when (item.mediaType) {
			MediaType.Video -> C.AUDIO_CONTENT_TYPE_MOVIE
			MediaType.Audio -> C.AUDIO_CONTENT_TYPE_MUSIC
			MediaType.Unknown -> C.AUDIO_CONTENT_TYPE_UNKNOWN
		}

		audioAttributeState.updateAudioAttributes(
			builder = {
				setContentType(contentType)
				setUsage(C.USAGE_MEDIA)
			},
			onChange = { audioAttributes ->
				exoPlayer.setAudioAttributes(audioAttributes, true)
				audioPassthroughSupported = null
				audioPassthroughSupportDirty = true
				refreshAudioPassthroughSupport(
					attributes = audioAttributes,
					allowReceiverRegistration = false,
				)
			}
		)

		fun startPlayback() {
			val isLiveTv = item.liveStreamTargetOffset != null
			val tsExtractorFlags = if (isLiveTv) item.liveTvTsExtractorFlags() else 0
			Timber.i(
				"Playback source check mediaId=%s isLiveTv=%s liveTargetOffsetMs=%s method=%s container=%s intendedTsExtractorFlags=%s uri=%s",
				stream.hashCode().toString(),
				isLiveTv,
				item.liveStreamTargetOffset?.inWholeMilliseconds?.toString() ?: "none",
				stream.conversionMethod,
				stream.container,
				formatTsExtractorFlags(tsExtractorFlags),
				stream.url.safeUriForLogging(),
			)
			exoPlayer.play()
		}

		clearPendingLiveStart()
		val liveStartBuffer = item.liveTvBufferDuration().takeIf { delayLiveStart }
		if (liveStartBuffer != null && liveStartBuffer > Duration.ZERO) {
			pendingLiveStartStream = stream
			exoPlayer.pause()
			listener?.onPlayStateChange(PlayState.BUFFERING)
			val deadlineMs = SystemClock.elapsedRealtime() + LIVE_START_TIMEOUT_MS
			startHandler.post(object : Runnable {
				override fun run() {
					if (pendingLiveStartStream != stream || currentStream != stream) return

					val timedOut = SystemClock.elapsedRealtime() >= deadlineMs
					if (shouldStartLivePlayback(
						isReady = exoPlayer.playbackState == Player.STATE_READY,
						bufferedDurationMs = exoPlayer.totalBufferedDuration,
						targetBufferDurationMs = liveStartBuffer.inWholeMilliseconds,
						timedOut = timedOut,
					)) {
						Timber.i(
							"Starting Live TV bufferedMs=%s targetMs=%s timedOut=%s",
							exoPlayer.totalBufferedDuration,
							liveStartBuffer.inWholeMilliseconds,
							timedOut,
						)
						pendingLiveStartStream = null
						startPlayback()
					} else {
						startHandler.postDelayed(this, LIVE_START_CHECK_INTERVAL_MS)
					}
				}
			})
		} else {
			startPlayback()
		}
	}

	override fun replaceItem(item: QueueEntry) {
		applyRendererPreferences()
		clearPendingLiveStart()
		val stream = requireNotNull(item.mediaStream)
		val player = exoPlayer
		val currentIndex = player.currentMediaItemIndex.takeIf { index ->
			index in 0 until player.mediaItemCount
		}

		currentStream = null
		if (item.liveStreamTargetOffset != null) {
			val mediaSource = item.createMediaSource(stream)
			if (currentIndex == null) {
				player.setMediaSource(mediaSource)
			} else {
				player.removeMediaItem(currentIndex)
				player.addMediaSource(currentIndex.coerceAtMost(player.mediaItemCount), mediaSource)
			}
		} else {
			val mediaItem = item.toMediaItem(stream)
			if (currentIndex == null) {
				player.setMediaItem(mediaItem)
			} else {
				player.replaceMediaItem(currentIndex, mediaItem)
			}
		}
		player.prepare()
		playItem(item, delayLiveStart = false)
	}

	override fun play() {
		if (pendingLiveStartStream != null && pendingLiveStartStream == currentStream) {
			listener?.onPlayStateChange(PlayState.BUFFERING)
			return
		}
		clearPendingLiveStart()
		// If the item has ended, revert first so the item will start over again
		if (exoPlayer.playbackState == Player.STATE_ENDED) {
			reportedEndedStream = null
			exoPlayer.seekTo(0)
		}
		exoPlayer.play()
	}

	override fun pause() {
		clearPendingLiveStart()
		exoPlayer.pause()
	}

	override fun stop() {
		clearPendingLiveStart()
		exoPlayer.stop()
		clearSubtitleCues()
		currentStream = null
		listener?.onSubtitleTimingOffsetSupportChange(false)
		reportedEndedStream = null
		clearPendingInitialTrackSelection()
		unregisterAudioCapabilitiesReceiver()
		setForcedVideoDecoder(null)
		resetPlaybackStats()
	}

	override fun seekTo(position: Duration) {
		if (!exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) || !exoPlayer.isCurrentMediaItemSeekable) {
			Timber.w("Trying to seek but ExoPlayer doesn't support it for the current item")
		}

		exoPlayer.seekTo(position.inWholeMilliseconds)
	}

	override fun setScrubbing(scrubbing: Boolean) {
		exoPlayer.isScrubbingModeEnabled = scrubbing
	}

	override fun setSpeed(speed: Float) {
		if (!exoPlayer.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
			Timber.w("Trying to change speed but ExoPlayer doesn't support it for the current item")
		}

		exoPlayer.setPlaybackSpeed(speed)
	}

	override fun getPositionInfo(): PositionInfo = getSafePositionInfo()

	override fun getFrameStats(): PlaybackFrameStats {
		val counters = exoPlayer.videoDecoderCounters
		counters?.ensureUpdated()
		refreshAudioPassthroughSupport(allowReceiverRegistration = true)

		return PlaybackFrameStats(
			droppedFrames = counters?.droppedBufferCount ?: 0,
			corruptedFrames = counters?.skippedInputBufferCount ?: 0,
			videoDecodedFrames = counters?.let {
				it.renderedOutputBufferCount + it.skippedOutputBufferCount + it.droppedBufferCount
			} ?: 0,
			videoDecoderName = videoDecoderName,
			videoDecoderType = videoDecoderType,
			videoHdrMode = videoInputFormat.hdrMode(),
			audioDecoderName = audioDecoderName,
			audioDecoderType = audioDecoderType,
			audioPassthroughSupported = audioPassthroughSupported,
			subtitleExtractor = subtitleExtractorDebug(),
			subtitleRender = subtitleRenderDebug(),
			subtitleParser = subtitleParserDebug(),
			subtitlePath = subtitlePathDebug(),
		)
	}

	private fun subtitleExtractorDebug(): String = when {
		exoPlayerOptions.enableLibass -> "AssMatroskaExtractor (MKV)"
		else -> "Media3 default"
	}

	private fun subtitleRenderDebug(): String = when {
		!exoPlayerOptions.enableLibass -> "Media3 cues"
		exoPlayerOptions.libassRenderType == AssRenderType.OVERLAY_OPEN_GL -> "libass OpenGL overlay"
		exoPlayerOptions.libassRenderType == AssRenderType.OVERLAY_CANVAS -> "libass Canvas overlay"
		exoPlayerOptions.libassRenderType == AssRenderType.CUES -> "Media3 cues"
		else -> exoPlayerOptions.libassRenderType.name
	}

	private fun subtitleParserDebug(): String = when {
		exoPlayerOptions.enableLibass && exoPlayerOptions.libassRenderType != AssRenderType.CUES -> "AssSubtitleParserFactory"
		else -> "DefaultSubtitleParserFactory"
	}

	private fun subtitlePathDebug(): String = when {
		exoPlayerOptions.enableLibass -> "libass renderer; extraction parser off"
		exoPlayerOptions.parseSubtitlesDuringExtraction -> "extraction parser"
		else -> "renderer parser"
	}

	private fun refreshAudioPassthroughSupport(
		format: Format? = audioInputFormat,
		attributes: AudioAttributes = audioAttributeState.audioAttributes ?: AudioAttributes.DEFAULT,
		capabilities: AudioCapabilities? = null,
		allowReceiverRegistration: Boolean,
	) {
		if (!audioPassthroughSupportDirty && capabilities == null) return

		if (format == null) {
			audioPassthroughSupported = null
			audioPassthroughSupportDirty = false
			return
		}

		if (!allowReceiverRegistration && !audioCapabilitiesReceiverRegistered && capabilities == null) return

		val resolvedCapabilities = capabilities ?: getAudioCapabilities(
			attributes = attributes,
			allowReceiverRegistration = allowReceiverRegistration,
		)
		audioPassthroughSupported = format.passthroughSupport(resolvedCapabilities, attributes)
		audioPassthroughSupportDirty = false
	}

	private fun getAudioCapabilities(
		attributes: AudioAttributes,
		allowReceiverRegistration: Boolean,
	): AudioCapabilities? {
		if (audioCapabilitiesReceiverFailed) return audioCapabilities
		if (!allowReceiverRegistration && !audioCapabilitiesReceiverRegistered) return audioCapabilities

		return runCatching {
			audioCapabilitiesReceiver.setAudioAttributes(attributes)
			if (allowReceiverRegistration && !audioCapabilitiesReceiverRegistered) {
				audioCapabilities = audioCapabilitiesReceiver.register()
				audioCapabilitiesReceiverRegistered = true
			}
			audioCapabilities
		}.onFailure { error ->
			Timber.w(error, "Failed to observe audio capabilities")
			runCatching { audioCapabilitiesReceiver.unregister() }
			audioCapabilitiesReceiverRegistered = false
			audioCapabilitiesReceiverFailed = true
			audioCapabilities = null
		}.getOrNull()
	}

	private fun Format.passthroughSupport(
		capabilities: AudioCapabilities?,
		attributes: AudioAttributes,
	): Boolean? {
		if (sampleMimeType.isNullOrBlank()) return null
		return capabilities?.isPassthroughPlaybackSupported(this, attributes)
	}

	override fun setTimedEvents(timedEvents: List<TimedEvent>) {
		timedEventState.setTimedEvents(exoPlayer, timedEvents)
	}

	override fun setSubtitleTiming(offset: Duration, speed: Float) {
		subtitleTimingOffsetState.setTiming(offset.inWholeMicroseconds, speed)
	}

	// TrackSelectionBackend implementation

	override fun getAvailableTracks(type: TrackType): List<PlayerTrack> {
		return getSourceTracks(type).mapIndexed { index, track ->
			val supportedTrack = if (currentStream?.conversionMethod == MediaConversionMethod.None) {
				findSupportedTrack(type, index, track)
			} else null
			val isSelected = supportedTrack?.groupInfo?.isTrackSelected(supportedTrack.trackIndex)
				?: isSelectedSourceTrack(type, track.index)

			PlayerTrack(
				index = index,
				type = type,
				label = track.displayTitle,
				language = track.displayLanguage,
				codec = track.codec,
				isSelected = isSelected,
				streamIndex = track.index,
				groupIndex = supportedTrack?.groupIndex ?: 0,
				trackIndex = supportedTrack?.trackIndex ?: 0,
			)
		}
	}

	override fun selectTrack(type: TrackType, index: Int): Boolean {
		clearPendingInitialTrackSelection()

		// Handle subtitle disable
		if (type == TrackType.SUBTITLE && index == -1) {
			if (currentStream?.conversionMethod != MediaConversionMethod.None) return false

			val params = exoPlayer.trackSelectionParameters.buildUpon()
				.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
				.build()
			exoPlayer.trackSelectionParameters = params
			return true
		}

		if (currentStream?.conversionMethod != MediaConversionMethod.None) return false

		val sourceTrack = getSourceTracks(type).getOrNull(index)
		val match = findSupportedTrack(type, index, sourceTrack)
		if (match == null) {
			Timber.w("Could not find track with index $index")
			return false
		}

		return applyTrackOverride(type.exoTrackType, match.group, match.trackIndex)
	}

	private fun findSupportedTrack(exoTrackType: Int, index: Int): SupportedTrack? {
		var currentIndex = 0
		for ((groupIndex, groupInfo) in exoPlayer.currentTracks.groups.withIndex()) {
			if (groupInfo.type != exoTrackType) continue

			val group = groupInfo.mediaTrackGroup
			for (i in 0 until group.length) {
				if (!groupInfo.isTrackSupported(i)) continue
				if (currentIndex == index) return SupportedTrack(groupInfo, group, groupIndex, i)
				currentIndex++
			}
		}
		return null
	}

	private fun findSupportedTrack(type: TrackType, sourceTrackIndex: Int, sourceTrack: MediaStreamTrack?): SupportedTrack? =
		when (type) {
			TrackType.AUDIO -> findSupportedTrack(type.exoTrackType, sourceTrackIndex)
			TrackType.SUBTITLE -> (sourceTrack as? MediaStreamSubtitleTrack)?.let(::findSupportedSubtitleTrack)
		}

	private fun findSupportedSubtitleTrack(track: MediaStreamSubtitleTrack): SupportedTrack? {
		val streamIndex = track.index ?: return null
		return if (track.isExternal) {
			val match = findSupportedTextTrack { group, trackIndex ->
				group.isExternalSubtitleTrack(trackIndex, streamIndex)
			}
			if (match == null) {
				Timber.w(
					"External subtitle track selection failed streamIndex=%s title=%s language=%s",
					streamIndex,
					track.displayTitle ?: "none",
					track.displayLanguage ?: "unknown",
				)
			} else {
				val format = match.group.getFormat(match.trackIndex)
				Timber.i(
					"External subtitle track matched streamIndex=%s groupIndex=%d trackIndex=%d groupId=%s formatId=%s mime=%s label=%s",
					streamIndex,
					match.groupIndex,
					match.trackIndex,
					match.group.id,
					format.id ?: "none",
					format.sampleMimeType ?: "unknown",
					format.label ?: "none",
				)
			}
			match
		} else {
			val internalSubtitleIndex = getSourceTracks(TrackType.SUBTITLE)
				.filterIsInstance<MediaStreamSubtitleTrack>()
				.filterNot(MediaStreamSubtitleTrack::isExternal)
				.indexOfFirst { subtitle -> subtitle.index == streamIndex }

			if (internalSubtitleIndex < 0) return null

			var currentIndex = 0
			findSupportedTextTrack { group, trackIndex ->
				if (group.isExternalSubtitleTrack(trackIndex)) return@findSupportedTextTrack false
				currentIndex++ == internalSubtitleIndex
			}
		}
	}

	private fun findSupportedTextTrack(predicate: (TrackGroup, Int) -> Boolean): SupportedTrack? {
		for ((groupIndex, groupInfo) in exoPlayer.currentTracks.groups.withIndex()) {
			if (groupInfo.type != C.TRACK_TYPE_TEXT) continue

			val group = groupInfo.mediaTrackGroup
			for (i in 0 until group.length) {
				if (!groupInfo.isTrackSupported(i)) continue
				if (predicate(group, i)) return SupportedTrack(groupInfo, group, groupIndex, i)
			}
		}
		return null
	}

	private fun TrackGroup.isExternalSubtitleTrack(trackIndex: Int, streamIndex: Int? = null): Boolean {
		val formatId = getFormat(trackIndex).id
		return id.isExternalSubtitleId(streamIndex) || formatId.isExternalSubtitleId(streamIndex)
	}

	private fun String?.isExternalSubtitleId(streamIndex: Int? = null): Boolean {
		if (this == null) return false
		if (streamIndex == null) {
			return contains("external:", ignoreCase = true) || contains("JF_EXTERNAL:", ignoreCase = true)
		}

		val externalMarkers = listOf("external:$streamIndex", "JF_EXTERNAL:$streamIndex")
		return externalMarkers.any { marker ->
			equals(marker, ignoreCase = true) || endsWith(":$marker", ignoreCase = true)
		}
	}

	private fun getSourceTracks(type: TrackType): List<MediaStreamTrack> = when (type) {
		TrackType.AUDIO -> currentStream?.tracks?.filterIsInstance<MediaStreamAudioTrack>()
		TrackType.SUBTITLE -> currentStream?.tracks?.filterIsInstance<MediaStreamSubtitleTrack>()
	}.orEmpty()

	private fun isSelectedSourceTrack(type: TrackType, streamIndex: Int?): Boolean {
		val stream = currentStream ?: return false
		return when (type) {
			TrackType.AUDIO -> stream.selectedAudioStreamIndex == streamIndex
			TrackType.SUBTITLE -> stream.selectedSubtitleStreamIndex == streamIndex
		}
	}

	private fun PlayableMediaStream.initialTrackSelection(): PendingInitialTrackSelection? {
		if (conversionMethod != MediaConversionMethod.None) return null
		if (selectedAudioStreamIndex == null && selectedSubtitleStreamIndex == null) return null

		return PendingInitialTrackSelection(
			stream = this,
			audioStreamIndex = selectedAudioStreamIndex,
			subtitleStreamIndex = selectedSubtitleStreamIndex,
		)
	}

	private fun applyPendingInitialTrackSelection() {
		val pending = pendingInitialTrackSelection ?: return
		val stream = currentStream ?: return
		if (pending.stream !== stream) {
			clearPendingInitialTrackSelection()
			return
		}
		if (!currentMediaItemMatchesCurrentStream()) return

		pending.audioStreamIndex?.let { streamIndex ->
			if (applyInitialTrackSelection(TrackType.AUDIO, streamIndex)) {
				pending.audioStreamIndex = null
			}
		}

		pending.subtitleStreamIndex?.let { streamIndex ->
			if (streamIndex < 0) {
				disableTextTracks()
				pending.subtitleStreamIndex = null
			} else if (applyInitialTrackSelection(TrackType.SUBTITLE, streamIndex)) {
				pending.subtitleStreamIndex = null
			}
		}

		if (pending.audioStreamIndex == null && pending.subtitleStreamIndex == null) {
			clearPendingInitialTrackSelection()
		}
	}

	private fun applyInitialTrackSelection(type: TrackType, streamIndex: Int): Boolean {
		val sourceTrackIndex = getSourceTracks(type).indexOfFirst { track -> track.index == streamIndex }
		if (sourceTrackIndex < 0) {
			Timber.w("Could not find ${type.name.lowercase()} stream index $streamIndex in current stream")
			return true
		}

		val sourceTrack = getSourceTracks(type).getOrNull(sourceTrackIndex)
		val match = findSupportedTrack(type, sourceTrackIndex, sourceTrack)
			?: return false

		return if (applyTrackOverride(type.exoTrackType, match.group, match.trackIndex)) {
			Timber.i("Applied initial ${type.name.lowercase()} stream index $streamIndex")
			true
		} else {
			true
		}
	}

	private fun disableTextTracks() {
		val params = exoPlayer.trackSelectionParameters.buildUpon()
			.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
			.build()
		exoPlayer.trackSelectionParameters = params
		Timber.i("Applied initial subtitle disabled selection")
	}

	private fun applyTrackOverride(exoTrackType: Int, group: TrackGroup, trackIndex: Int): Boolean = try {
		val params = exoPlayer.trackSelectionParameters.buildUpon()
			.setTrackTypeDisabled(exoTrackType, false)
			.setOverrideForType(TrackSelectionOverride(group, trackIndex))
			.build()
		exoPlayer.trackSelectionParameters = params
		true
	} catch (e: IllegalArgumentException) {
		Timber.w(e, "Error setting track selection")
		false
	}

	private val TrackType.exoTrackType: Int
		get() = when (this) {
			TrackType.AUDIO -> C.TRACK_TYPE_AUDIO
			TrackType.SUBTITLE -> C.TRACK_TYPE_TEXT
		}

	private val MediaStreamTrack.displayTitle: String?
		get() = when (this) {
			is MediaStreamAudioTrack -> title
			is MediaStreamSubtitleTrack -> title
			else -> null
		}

	private val MediaStreamTrack.displayLanguage: String?
		get() = when (this) {
			is MediaStreamAudioTrack -> language
			is MediaStreamSubtitleTrack -> language
			else -> null
		}

	private data class SupportedTrack(
		val groupInfo: Tracks.Group,
		val group: TrackGroup,
		val groupIndex: Int,
		val trackIndex: Int,
	)

	private data class PendingInitialTrackSelection(
		val stream: PlayableMediaStream,
		var audioStreamIndex: Int?,
		var subtitleStreamIndex: Int?,
	)

	private val ExternalSubtitle.selectionFlags: Int
		get() {
			var flags = 0
			if (isDefault) flags = flags or C.SELECTION_FLAG_DEFAULT
			if (isForced) flags = flags or C.SELECTION_FLAG_FORCED
			return flags
		}

	private inner class LiveTvMediaSourceFactory(
		private val defaultFactory: MediaSource.Factory,
		private val liveTvFactory: MediaSource.Factory,
		private val amlogicH264LiveTvFactory: MediaSource.Factory,
	) : MediaSource.Factory {
		override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
			defaultFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
			liveTvFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
			amlogicH264LiveTvFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
			return this
		}

		override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
			defaultFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
			liveTvFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
			amlogicH264LiveTvFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
			return this
		}

		override fun getSupportedTypes(): IntArray = defaultFactory.supportedTypes

		override fun createMediaSource(mediaItem: MediaItem): MediaSource {
			val queueEntry = mediaItem.localConfiguration?.tag as? QueueEntry
			val isLiveTv = queueEntry?.liveStreamTargetOffset != null
			val tsExtractorFlags = if (isLiveTv) queueEntry.liveTvTsExtractorFlags() else 0
			val factory = when (tsExtractorFlags) {
				DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES -> liveTvFactory
				DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS -> amlogicH264LiveTvFactory
				else -> defaultFactory
			}

			Timber.i(
				"Creating %s media source for mediaId=%s uri=%s liveTargetOffsetMs=%s tsExtractorFlags=%s",
				if (isLiveTv) "Live TV" else "default",
				mediaItem.mediaId,
				mediaItem.safeUriForLogging(),
				queueEntry?.liveStreamTargetOffset?.inWholeMilliseconds?.toString() ?: "none",
				formatTsExtractorFlags(tsExtractorFlags),
			)

			return factory.createMediaSource(mediaItem)
		}
	}
}

@OptIn(UnstableApi::class)
internal fun Format?.hdrMode(): String? {
	val format = this ?: return null
	if (format.sampleMimeType == "video/dolby-vision") return format.dolbyVisionMode()

	return when (format.colorInfo?.colorTransfer) {
		C.COLOR_TRANSFER_ST2084 -> "HDR10/PQ"
		C.COLOR_TRANSFER_HLG -> "HLG"
		C.COLOR_TRANSFER_SDR -> "SDR"
		C.COLOR_TRANSFER_SRGB -> "sRGB"
		C.COLOR_TRANSFER_LINEAR -> "Linear"
		C.COLOR_TRANSFER_GAMMA_2_2 -> "Gamma 2.2"
		else -> null
	}
}

private fun Format.dolbyVisionMode(): String = codecs
	?.split(',')
	?.firstNotNullOfOrNull { codec ->
		val parts = codec.trim().split('.')
		val prefix = parts.firstOrNull()
		val profile = parts.getOrNull(1)?.toIntOrNull()
		when {
			profile == null -> null
			prefix == "dav1" -> "DV AV1 P$profile"
			prefix?.startsWith("dv") == true -> "DV P$profile"
			else -> null
		}
	}
	?: "Dolby Vision"
