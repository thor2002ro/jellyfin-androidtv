package org.jellyfin.playback.media3.exoplayer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.DefaultExtractorsFactory
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
import org.jellyfin.playback.media3.exoplayer.subtitle.isSubtitleTimingOffsetSupported
import org.jellyfin.playback.media3.exoplayer.support.getPlaySupportReport
import org.jellyfin.playback.media3.exoplayer.support.toFormats
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
class ExoPlayerBackend(
	private val context: Context,
	private val exoPlayerOptions: ExoPlayerOptions,
) : BasePlayerBackend(), TrackSelectionBackend {
	companion object {
		const val TS_SEARCH_BYTES = 3 * TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
		const val MEDIA_ITEM_COUNT_MAX = 10
		private val LIVE_TV_TS_EXTRACTOR_FLAGS =
			DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
				DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
		private const val LIVE_TV_TS_EXTRACTOR_FLAG_NAMES = "DETECT_ACCESS_UNITS|ALLOW_NON_IDR_KEYFRAMES"

		private fun formatTsExtractorFlags(flags: Int) =
			if (flags == 0) "none (0)" else "$LIVE_TV_TS_EXTRACTOR_FLAG_NAMES ($flags)"

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
	private var subtitleSurfaceView: PlayerSubtitleView? = null
	private var subtitleView: SubtitleView? = null
	private val audioPipeline = ExoPlayerAudioPipeline()
	private val audioAttributeState = AudioAttributeState()
	private val timedEventState = TimedEventState()
	private var lastKnownDuration: Duration? = null
	private val subtitleTimingOffsetState = SubtitleTimingOffsetState()
	private val startHandler = Handler(Looper.getMainLooper())
	private var pendingInitialTrackSelection: PendingInitialTrackSelection? = null
	private var videoDecoderName: String? = null
	private var audioDecoderName: String? = null
	private var audioInputFormat: Format? = null
	private var audioPassthroughSupported: Boolean? = null
	private var audioPassthroughSupportDirty = true
	private var audioCapabilities: AudioCapabilities? = null
	private var audioCapabilitiesReceiverRegistered = false
	private var audioCapabilitiesReceiverFailed = false
	private var reportedPlayState: PlayState? = null
	private var reportedEndedStream: PlayableMediaStream? = null
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

	private fun QueueEntry.liveTvBufferDuration(): Duration? {
		val liveStreamOffset = liveStreamTargetOffset ?: return null
		val configuredOffset = exoPlayerOptions.liveTvBufferDuration ?: return liveStreamOffset
		return maxOf(liveStreamOffset, configuredOffset)
	}

	private fun resetPlaybackStats() {
		videoDecoderName = null
		audioDecoderName = null
		audioInputFormat = null
		audioPassthroughSupported = null
		audioPassthroughSupportDirty = true
		audioCapabilitiesReceiverFailed = false
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

	private val exoPlayer by lazy {
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
			experimentalParseSubtitlesDuringExtraction(exoPlayerOptions.parseSubtitlesDuringExtraction)
			setSubtitleParserFactory(subtitleParserFactory)
		}

		fun MediaSource.Factory.withExternalSubtitlesInRenderer() =
			ExternalSubtitleMediaSourceFactory(this, dataSourceFactory)

		val normalExtractorsFactory = createExtractorsFactory()
		val liveTvExtractorsFactory = createExtractorsFactory(LIVE_TV_TS_EXTRACTOR_FLAGS)
		val renderersFactory: RenderersFactory
		if (exoPlayerOptions.enableLibass) {
			val assSubtitleParserFactory = AssSubtitleParserFactory(assHandler)
			val normalMediaSourceFactory = DefaultMediaSourceFactory(
				dataSourceFactory,
				normalExtractorsFactory.withAssMkvSupport(assSubtitleParserFactory, assHandler),
			).configureSubtitles(assSubtitleParserFactory)
			val liveTvMediaSourceFactory = DefaultMediaSourceFactory(
				dataSourceFactory,
				liveTvExtractorsFactory.withAssMkvSupport(assSubtitleParserFactory, assHandler),
			).configureSubtitles(assSubtitleParserFactory)
			mediaSourceFactory = LiveTvMediaSourceFactory(normalMediaSourceFactory, liveTvMediaSourceFactory)
				.withExternalSubtitlesInRenderer()
			renderersFactory = SubtitleTimingOffsetRenderersFactory(
				context = context,
				offsetState = subtitleTimingOffsetState,
				subtitleParserFactory = assSubtitleParserFactory,
			).apply {
				setEnableDecoderFallback(true)
				setExtensionRendererMode(
					when (exoPlayerOptions.preferFfmpeg) {
						true -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
						false -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
					}
				)
			}.let { AssRenderersFactory(assHandler, it) }
		} else {
			val defaultSubtitleParserFactory = DefaultSubtitleParserFactory()
			val normalMediaSourceFactory = DefaultMediaSourceFactory(
				dataSourceFactory,
				normalExtractorsFactory,
			).configureSubtitles(defaultSubtitleParserFactory)
			val liveTvMediaSourceFactory = DefaultMediaSourceFactory(
				dataSourceFactory,
				liveTvExtractorsFactory,
			).configureSubtitles(defaultSubtitleParserFactory)
			mediaSourceFactory = LiveTvMediaSourceFactory(normalMediaSourceFactory, liveTvMediaSourceFactory)
				.withExternalSubtitlesInRenderer()
			renderersFactory = SubtitleTimingOffsetRenderersFactory(
				context = context,
				offsetState = subtitleTimingOffsetState,
				subtitleParserFactory = defaultSubtitleParserFactory,
			).apply {
				setEnableDecoderFallback(true)
				setExtensionRendererMode(
					when (exoPlayerOptions.preferFfmpeg) {
						true -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
						false -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
					}
				)
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

		ExoPlayer.Builder(context)
			.setLoadControl(loadControl)
			.setRenderersFactory(renderersFactory)
			.setTrackSelector(DefaultTrackSelector(context).apply {
				setParameters(buildUponParameters().apply {
					setAudioOffloadPreferences(
						TrackSelectionParameters.AudioOffloadPreferences.DEFAULT.buildUpon().apply {
							setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
						}.build()
					)
					setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
				})
			})
			.setMediaSourceFactory(mediaSourceFactory)
			.setPauseAtEndOfMediaItems(true)
			.build()
			.also { player ->
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

	inner class DecoderAnalyticsListener : AnalyticsListener {
		override fun onVideoDecoderInitialized(
			eventTime: AnalyticsListener.EventTime,
			decoderName: String,
			initializedTimestampMs: Long,
			initializationDurationMs: Long,
		) {
			videoDecoderName = decoderName
		}

		override fun onVideoDecoderReleased(
			eventTime: AnalyticsListener.EventTime,
			decoderName: String,
		) {
			if (videoDecoderName == decoderName) videoDecoderName = null
		}

		override fun onVideoDisabled(
			eventTime: AnalyticsListener.EventTime,
			decoderCounters: DecoderCounters,
		) {
			videoDecoderName = null
		}

		override fun onAudioDecoderInitialized(
			eventTime: AnalyticsListener.EventTime,
			decoderName: String,
			initializedTimestampMs: Long,
			initializationDurationMs: Long,
		) {
			audioDecoderName = decoderName
		}

		override fun onAudioDecoderReleased(
			eventTime: AnalyticsListener.EventTime,
			decoderName: String,
		) {
			if (audioDecoderName == decoderName) audioDecoderName = null
		}

		override fun onAudioInputFormatChanged(
			eventTime: AnalyticsListener.EventTime,
			format: Format,
			decoderReuseEvaluation: DecoderReuseEvaluation?,
		) {
			audioInputFormat = format
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
			audioDecoderName = null
			audioInputFormat = null
			audioPassthroughSupported = null
			audioPassthroughSupportDirty = true
			audioCapabilitiesReceiverFailed = false
			unregisterAudioCapabilitiesReceiver()
		}
	}

	inner class PlayerListener : Player.Listener {
		override fun onIsPlayingChanged(isPlaying: Boolean) {
			val state = when {
				isPlaying -> PlayState.PLAYING
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
			onIsPlayingChanged(exoPlayer.isPlaying)
			if (playbackState == Player.STATE_ENDED) {
				reportCurrentMediaStreamEnd("playback-state-ended")
			}
		}

		override fun onTracksChanged(tracks: Tracks) {
			applyPendingInitialTrackSelection()

			val canAdjustSubtitleTiming = tracks.groups
				.asSequence()
				.filter { it.type == C.TRACK_TYPE_TEXT }
				.flatMap { group -> (0 until group.length).asSequence().filter(group::isTrackSelected).map(group::getTrackFormat) }
				.any(::isSubtitleTimingOffsetSupported)

			listener?.onSubtitleTimingOffsetSupportChange(canAdjustSubtitleTiming)
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
		currentStream = stream
		reportedEndedStream = null
		resetPlaybackStats()
		pendingInitialTrackSelection = stream.initialTrackSelection()

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
			val tsExtractorFlags = if (isLiveTv) LIVE_TV_TS_EXTRACTOR_FLAGS else 0
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
			Timber.i("Playing ${item.mediaStream?.url}")
			exoPlayer.play()
		}

		startHandler.removeCallbacksAndMessages(null)
		val liveStartDelay = item.liveTvBufferDuration().takeIf { delayLiveStart }
		if (liveStartDelay != null && liveStartDelay > Duration.ZERO) {
			exoPlayer.pause()
			startHandler.postDelayed({
				if (currentStream == stream) startPlayback()
			}, liveStartDelay.inWholeMilliseconds)
		} else {
			startPlayback()
		}
	}

	override fun replaceItem(item: QueueEntry) {
		startHandler.removeCallbacksAndMessages(null)
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
		startHandler.removeCallbacksAndMessages(null)
		// If the item has ended, revert first so the item will start over again
		if (exoPlayer.playbackState == Player.STATE_ENDED) {
			reportedEndedStream = null
			exoPlayer.seekTo(0)
		}
		exoPlayer.play()
	}

	override fun pause() {
		startHandler.removeCallbacksAndMessages(null)
		exoPlayer.pause()
	}

	override fun stop() {
		startHandler.removeCallbacksAndMessages(null)
		exoPlayer.stop()
		clearSubtitleCues()
		currentStream = null
		reportedEndedStream = null
		pendingInitialTrackSelection = null
		unregisterAudioCapabilitiesReceiver()
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
			audioDecoderName = audioDecoderName,
			audioPassthroughSupported = audioPassthroughSupported,
		)
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

	override fun setSubtitleTimingOffset(offset: Duration) {
		subtitleTimingOffsetState.setOffsetUs(offset.inWholeMicroseconds)
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
		pendingInitialTrackSelection = null

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
			pendingInitialTrackSelection = null
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
			pendingInitialTrackSelection = null
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

	private class LiveTvMediaSourceFactory(
		private val defaultFactory: MediaSource.Factory,
		private val liveTvFactory: MediaSource.Factory,
	) : MediaSource.Factory {
		override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
			defaultFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
			liveTvFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
			return this
		}

		override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
			defaultFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
			liveTvFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
			return this
		}

		override fun getSupportedTypes(): IntArray = defaultFactory.supportedTypes

		override fun createMediaSource(mediaItem: MediaItem): MediaSource {
			val queueEntry = mediaItem.localConfiguration?.tag as? QueueEntry
			val isLiveTv = queueEntry?.liveStreamTargetOffset != null
			val tsExtractorFlags = if (isLiveTv) LIVE_TV_TS_EXTRACTOR_FLAGS else 0
			val factory = when {
				isLiveTv -> liveTvFactory
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
