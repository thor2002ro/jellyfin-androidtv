package org.jellyfin.playback.libvlc

import android.content.Context
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.jellyfin.playback.core.PlaybackBufferOptions
import org.jellyfin.playback.core.surroundVolumeBoost
import org.jellyfin.playback.core.backend.BasePlayerBackend
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.activate
import org.jellyfin.playback.core.backend.PlayerTrack
import org.jellyfin.playback.core.backend.TrackSelectionBackend
import org.jellyfin.playback.core.backend.TrackType
import org.jellyfin.playback.core.backend.VideoDecoderOption
import org.jellyfin.playback.core.mediastream.ExternalSubtitle
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.core.mediastream.MediaStreamTrack
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.allowsLocalSubtitleDisable
import org.jellyfin.playback.core.mediastream.allowsLocalTrackSelection
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.startPosition
import org.jellyfin.playback.core.mediastream.totalBitrate
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PlaybackFrameStats
import org.jellyfin.playback.core.model.formatBufferBytes
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.model.VideoGeometry
import org.jellyfin.playback.core.model.VideoOutputTransform
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.isLiveTv
import org.jellyfin.playback.core.support.PlaySupportReport
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.timedevent.TimedEventTracker
import org.jellyfin.playback.core.ui.PlayerSubtitleStyle
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCUtil
import org.videolan.libvlc.util.HWDecoderUtil
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class LibVLCVideoDecoder(val vlcValue: Int, val label: String) {
	AUTOMATIC(-1, "Auto"),
	DISABLED(0, "Software"),
	DECODING(1, "Decoding"),
	FULL(2, "Hardware with direct rendering"),
}

data class LibVLCPlaybackOptions(
	val surroundVolumeBoost: Int = 6,
	val deblocking: Int = -1,
	val frameSkip: Boolean = false,
	val audioTimeStretch: Boolean = false,
	val dav1dThreadFrames: Int = 0,
)

data class LibVLCInstanceOptions(
	val arguments: List<String> = emptyList(),
	val audioOutput: String? = null,
)

internal fun Media.setVideoDecoder(decoder: LibVLCVideoDecoder) {
	when (decoder) {
		LibVLCVideoDecoder.AUTOMATIC -> Unit
		LibVLCVideoDecoder.DISABLED -> setHWDecoderEnabled(false, false)
		LibVLCVideoDecoder.DECODING -> {
			setHWDecoderEnabled(true, true)
			addOption(":no-mediacodec-dr")
			addOption(":no-omxil-dr")
		}
		LibVLCVideoDecoder.FULL -> setHWDecoderEnabled(true, true)
	}
}

internal fun libVLCMediaOptions(
	isLiveTv: Boolean,
	normalBufferDuration: Duration?,
	liveTvBufferDuration: Duration?,
	maxBufferBytes: Long?,
	bitrate: Long,
	options: LibVLCPlaybackOptions,
	subtitlesDisabled: Boolean = false,
): List<String> = buildList {
	if (subtitlesDisabled) add(":sub-language=none")
	add(if (options.audioTimeStretch) ":audio-time-stretch" else ":no-audio-time-stretch")
	add(":avcodec-skiploopfilter=${resolveDeblocking(options.deblocking)}")
	add(":avcodec-skip-frame=${if (options.frameSkip) 2 else 0}")
	add(":avcodec-skip-idct=${if (options.frameSkip) 2 else 0}")
	add(":stats")
	add(":audio-resampler=soxr")
	if (options.dav1dThreadFrames >= 1) add(":dav1d-thread-frames=${options.dav1dThreadFrames}")

	val networkCachingMs = cappedBufferDuration(
		duration = if (isLiveTv) liveTvBufferDuration else normalBufferDuration,
		maxBufferBytes = maxBufferBytes,
		bitrate = bitrate,
	)
		?.inWholeMilliseconds
	if (networkCachingMs != null) {
		add(":network-caching=$networkCachingMs")
	}
}

internal fun cappedBufferDuration(duration: Duration?, maxBufferBytes: Long?, bitrate: Long): Duration? {
	if (duration == null || maxBufferBytes == null || maxBufferBytes <= 0 || bitrate <= 0) return duration
	return minOf(duration, (maxBufferBytes * 8.0 / bitrate).seconds)
}

internal fun resolveDeblocking(
	deblocking: Int,
	machineSpecs: VLCUtil.MachineSpecs? = VLCUtil.getMachineSpecs(),
): Int {
	if (deblocking >= 0) return deblocking.takeIf { it <= 4 } ?: 3
	val specs = machineSpecs ?: return deblocking
	return when {
		specs.hasArmV6 && !specs.hasArmV7 || specs.hasMips -> 4
		(specs.frequency >= 1200 || specs.bogoMIPS >= 1200) && specs.processors > 2 -> 1
		else -> 3
	}
}

internal fun PlayerSubtitleStyle.libVLCOptions() = buildList {
	add("--freetype-rel-fontsize=${textSizeDp.roundToInt().coerceIn(8, 32)}")
	if (textWeight >= 600) add("--freetype-bold")
	add("--freetype-color=${textColor.rgb()}")
	add("--freetype-opacity=${textColor.alpha()}")
	add("--freetype-background-color=${backgroundColor.rgb()}")
	add("--freetype-background-opacity=${backgroundColor.alpha()}")
	add("--freetype-outline-thickness=4")
	add("--freetype-outline-color=${edgeColor.rgb()}")
	add("--freetype-outline-opacity=${edgeColor.alpha()}")
}

private fun Int.rgb() = this and 0x00FF_FFFF

private fun Int.alpha() = this ushr 24 and 0xFF

internal fun shouldSelectExternalSubtitle(
	subtitle: ExternalSubtitle,
	selectedSubtitleStreamIndex: Int?,
) = when (selectedSubtitleStreamIndex) {
	null -> subtitle.isDefault
	else -> subtitle.index == selectedSubtitleStreamIndex
}

internal fun PlayableMediaStream.libVLCSourceTracks(
	type: TrackType,
	embeddedSubtitleCount: Int = Int.MAX_VALUE,
): List<MediaStreamTrack> = when (type) {
	TrackType.AUDIO -> tracks.filterIsInstance<MediaStreamAudioTrack>()
	TrackType.SUBTITLE -> {
		val subtitles = tracks.filterIsInstance<MediaStreamSubtitleTrack>()
		// Converted streams can omit embedded subtitles or carry only the selected one.
		val embedded = subtitles.filter { !it.isExternal && (conversionMethod == MediaConversionMethod.None || it.index == selectedSubtitleStreamIndex) }
		embedded.take(embeddedSubtitleCount) + externalSubtitles.mapNotNull { external ->
			subtitles.firstOrNull { subtitle -> subtitle.isExternal && subtitle.index == external.index }
		}
	}
}

internal fun PlayableMediaStream.sourceTrackIndex(type: TrackType, streamIndex: Int, embeddedSubtitleCount: Int = Int.MAX_VALUE): Int? =
	libVLCSourceTracks(type, embeddedSubtitleCount)
		.indexOfFirst { track -> track.index == streamIndex }
		.takeIf { index -> index >= 0 }

internal fun orderedLibVLCTrackIds(
	mediaTrackIds: List<Int>,
	descriptionTrackIds: List<Int>,
): List<Int> {
	val selectableIds = descriptionTrackIds.filter { it >= 0 }.distinct()
	val selectableIdSet = selectableIds.toHashSet()
	val orderedIds = mediaTrackIds.filter { trackId -> trackId in selectableIdSet }.distinct().toMutableList()
	val orderedIdSet = orderedIds.toHashSet()
	orderedIds += selectableIds.filter(orderedIdSet::add)
	return orderedIds
}

internal class CoalescingViewAttachScheduler(
	private val post: (Runnable) -> Unit,
	private val remove: (Runnable) -> Unit,
	attach: () -> Unit,
) {
	private val attachRunnable = Runnable(attach)

	fun schedule() {
		remove(attachRunnable)
		post(attachRunnable)
	}

	fun cancel() = remove(attachRunnable)
}

class LibVLCBackend(
	context: Context,
	private val instanceOptionsProvider: () -> LibVLCInstanceOptions = { LibVLCInstanceOptions() },
	private val videoDecoderProvider: (() -> LibVLCVideoDecoder)? = null,
	private val playbackOptionsProvider: (() -> LibVLCPlaybackOptions)? = null,
	nativeLogSink: (String) -> Unit = {},
) : BasePlayerBackend(), TrackSelectionBackend, IVLCVout.OnNewVideoLayoutListener, IVLCVout.Callback {
	private companion object {
		const val TICK_INTERVAL_MS = 250L
	}

	override val reportsBufferedPosition = false
	override val supportsSubtitleTimingSpeed = false

	var videoDecoder = LibVLCVideoDecoder.AUTOMATIC
		private set
	private var forcedVideoDecoder: LibVLCVideoDecoder? = null
	private val effectiveVideoDecoder: LibVLCVideoDecoder
		get() = forcedVideoDecoder ?: videoDecoder
	private var playbackOptions = LibVLCPlaybackOptions()
	override val videoDecoderOptions = LibVLCVideoDecoder.entries.map { decoder ->
		VideoDecoderOption(
			id = decoder.name,
			label = decoder.label,
		)
	}
	override val selectedVideoDecoderOption: VideoDecoderOption
		get() = requireNotNull(effectiveVideoDecoder.toOption())
	override val forcedVideoDecoderOption: VideoDecoderOption?
		get() = forcedVideoDecoder?.toOption()

	private val appContext = context.applicationContext
	private var subtitleStyle: PlayerSubtitleStyle? = null
	private var subtitleTimingOffset = Duration.ZERO
	private var bufferingPercent = 100f
	private var bufferingReceived = false
	private var playbackActive = false
	private var pendingStartPosition: Duration? = null
	private var initialSeekFailed = false
	private var initialPositionForReporting: Duration? = null
	private var startPlayWhenReady = true
	private var playbackSpeed = 1f
	private var audioOutputChannels: Int? = null
	private var mediaReady = false
	private var inputStarted = false
	private var surfacesReady = false
	private var attachedVideoView: SurfaceView? = null
	private var attachedSubtitleView: SurfaceView? = null
	private var attachedPlayer: MediaPlayer? = null
	private val stoppingPlayers = mutableSetOf<MediaPlayer>()
	private val pendingSubtitleSurfaceRemovals = mutableListOf<Pair<MediaPlayer, SurfaceView>>()
	private var playRequested = false
	private var videoOutputCount = 0
	private val mediaGeneration = AtomicLong()
	// Loading and stopping can block in VLC; keep them off the UI thread and in the order requested.
	private val nativeExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "vlc-media") }
	private var appliedInstanceOptions = currentInstanceOptions()
	private var libVLC = createLibVLC(appliedInstanceOptions)
	private var player = createPlayer(libVLC, appliedInstanceOptions)
	private val videoOutput = LibVLCVideoOutput { aspectRatio -> if (mediaReady && !released) player.setAspectRatio(aspectRatio) }
	private val handler = Handler(Looper.getMainLooper())
	private val viewAttachScheduler = CoalescingViewAttachScheduler(
		post = { runnable -> handler.post(runnable) },
		remove = handler::removeCallbacks,
		attach = ::attachViewsNow,
	)
	private val timedEvents = TimedEventTracker()
	private var normalBufferDuration: Duration? = null
	private var liveTvBufferDuration: Duration? = null
	private var maxBufferBytes: Long? = null
	private var previousStatsSample: LibVLCStatsSample? = null
	private val decoderLogReader = LibVLCDecoderLogReader(
		LibVLCDecoderLogParser(),
		logSink = nativeLogSink,
		onAudioOutputChanged = { session, output ->
			handler.post {
				if (!released && currentStream != null && session == decoderSessionCounter.toString()) {
					observedAudioOutput = output
					updateSurroundVolumeBoost()
				}
			}
		},
	)
	private var decoderSessionCounter = 0L
	private var currentStream: PlayableMediaStream? = null
	private var observedAudioOutput: LibVLCAudioOutput? = null
	private var surfaceView: PlayerSurfaceView? = null
	private var videoSurfaceWidthScale = 1.0
	private var videoSurfaceHeightScale = 1.0
	private var subtitleView: PlayerSubtitleView? = null
	private var subtitleSurface: SurfaceView? = null
	private var subtitleLayoutListener: View.OnLayoutChangeListener? = null
	private var lastTickPosition = Duration.ZERO
	private var endReported = false
	private var released = false
	private val pendingInitialTrackTypes = mutableSetOf<TrackType>()

	private val tick = object : Runnable {
		override fun run() {
			val position = getPositionInfo()
			timedEvents.advance(lastTickPosition, position.active, position.duration, natural = true)
			lastTickPosition = position.active
			handler.postDelayed(this, TICK_INTERVAL_MS)
		}
	}

	private var surroundBoostGain = 0
	private var surroundBoostRequest: Int? = null
	private var surroundBoostAllowed = false

	private fun updateSurroundVolumeBoost() {
		if (!surroundBoostAllowed || playbackOptions.surroundVolumeBoost <= 0) {
			setSurroundBoostGain(0)
			return
		}
		// A surround source may have been downmixed to stereo, so source channels cannot decide the boost.
		val channels = audioOutputChannels ?: observedAudioOutput?.channels
		val gain = surroundVolumeBoost(playbackOptions.surroundVolumeBoost, channels, surroundBoostAllowed)
		setSurroundBoostGain(gain)
	}

	private fun setSurroundBoostGain(gain: Int) {
		if (gain == surroundBoostRequest) return
		surroundBoostRequest = gain
		if (gain == surroundBoostGain) return
		val equalizer = if (gain == 0) null else MediaPlayer.Equalizer.create().apply { setPreAmp(gain.toFloat()) }
		if (player.setEqualizer(equalizer)) surroundBoostGain = gain
		else Timber.w("VLC rejected surround volume boost of %s dB", gain)
	}

	override fun supportsStream(stream: MediaStream): PlaySupportReport = object : PlaySupportReport {
		override val canPlay = true
	}

	override fun setSurfaceView(surfaceView: PlayerSurfaceView?) {
		this.surfaceView?.removeOnLayoutChangeListener(videoLayoutListener)
		this.surfaceView = surfaceView
		surfaceView?.addOnLayoutChangeListener(videoLayoutListener)
		updateViews()
	}

	private val videoLayoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
		if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) updateVideoWindowSize()
	}

	private fun updateVideoWindowSize() {
		val view = surfaceView ?: return
		if (released || view.width <= 0 || view.height <= 0) return
		player.vlcVout.setWindowSize(view.width, view.height)
		val video = attachedVideoView ?: return
		// The shared viewport fits the visible frame; include coded padding in the inner surface.
		val layoutWidth = kotlin.math.ceil(view.width * videoSurfaceWidthScale).toInt()
		val layoutHeight = kotlin.math.ceil(view.height * videoSurfaceHeightScale).toInt()
		if (video.layoutParams.width != layoutWidth || video.layoutParams.height != layoutHeight) {
			video.layoutParams = FrameLayout.LayoutParams(layoutWidth, layoutHeight, Gravity.CENTER)
		}
	}

	override fun setSubtitleView(surfaceView: PlayerSubtitleView?) {
		subtitleView?.onSubtitleStyleChanged = null
		subtitleLayoutListener?.let { listener -> subtitleView?.removeOnLayoutChangeListener(listener) }
		subtitleLayoutListener = null
		subtitleSurface?.let { surface ->
			val owner = attachedPlayer?.takeIf { attachedSubtitleView === surface && it in stoppingPlayers }
			if (owner != null) pendingSubtitleSurfaceRemovals += owner to surface
			else (surface.parent as? ViewGroup)?.removeView(surface)
		}
		subtitleView = surfaceView
		subtitleSurface = surfaceView?.let { view ->
			val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
				applySubtitleSurfaceStyle(view.subtitleStyle)
			}
			subtitleLayoutListener = layoutListener
			view.addOnLayoutChangeListener(layoutListener)
			SurfaceView(view.context).also { subtitleSurface ->
				subtitleSurface.setZOrderMediaOverlay(true)
				subtitleSurface.holder.setFormat(PixelFormat.TRANSLUCENT)
				view.addView(
					subtitleSurface,
					FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
				)
				applySubtitleSurfaceStyle(view.subtitleStyle)
				view.onSubtitleStyleChanged = ::applySubtitleSurfaceStyle
			}
		}
		updateViews()
	}

	override fun setVideoOutputTransform(transform: VideoOutputTransform) {
		videoOutput.apply(transform)
	}

	private fun applySubtitleSurfaceStyle(subtitleStyle: PlayerSubtitleStyle) {
		this.subtitleStyle = subtitleStyle
		val view = subtitleView ?: return
		val surface = subtitleSurface ?: return
		val bottomMargin = (view.height * subtitleStyle.bottomPaddingFraction)
			.roundToInt()
			.coerceIn(0, view.height.coerceAtLeast(0))
		val currentLayoutParams = surface.layoutParams as? FrameLayout.LayoutParams
		if (currentLayoutParams?.bottomMargin == bottomMargin) return

		surface.layoutParams = FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.MATCH_PARENT,
			FrameLayout.LayoutParams.MATCH_PARENT,
		).apply {
			this.bottomMargin = bottomMargin
		}
	}

	private fun updateViews() {
		if (surfaceView == null) {
			viewAttachScheduler.cancel()
			if (player !in stoppingPlayers) player.vlcVout.detachViews()
		} else if (mediaReady) {
			viewAttachScheduler.schedule()
		}
	}

	private fun attachViewsNow() {
		if (released) return
		val video = surfaceView?.surface ?: return
		if (player.vlcVout.areViewsAttached() && attachedVideoView === video && attachedSubtitleView === subtitleSurface) {
			updateVideoWindowSize()
			startPlaybackIfReady()
			return
		}
		surfacesReady = false
		player.vlcVout.detachViews()
		attachedVideoView = video
		attachedSubtitleView = subtitleSurface
		attachedPlayer = player
		player.vlcVout.setVideoView(video)
		subtitleSurface?.let(player.vlcVout::setSubtitlesView)
		updateVideoWindowSize()
		player.vlcVout.attachViews(this)
		startPlaybackIfReady()
	}

	private fun startPlaybackIfReady() {
		// Video needs real surfaces, not just attached views. Audio-only items can start without either.
		val needsVideo = currentStream?.tracks?.any { it is MediaStreamVideoTrack } == true
		if (released || !mediaReady || !playRequested || (needsVideo && !surfacesReady)) return
		if (startPlayWhenReady || pendingStartPosition != null) {
			if (!inputStarted) {
				player.media?.let { media ->
					try {
						// Choose this at the last moment so a seek made during loading is included before any frames appear.
						if (pendingStartPosition != null) media.addOption(":start-paused")
					} finally { media.release() }
				}
				inputStarted = true
			}
			playRequested = false
			player.play()
		}
	}

	override fun onSurfacesCreated(vout: IVLCVout) {
		if (released || vout !== player.vlcVout) return
		surfacesReady = true
		startPlaybackIfReady()
	}

	override fun onSurfacesDestroyed(vout: IVLCVout) {
		if (released || vout !== player.vlcVout) return
		surfacesReady = false
	}

	override fun prepareItem(item: QueueEntry) = Unit

	override fun playItem(item: QueueEntry) {
		if (released) return
		val stream = requireNotNull(item.mediaStream)
		if (mediaReady && stream == currentStream && player.isPlaying) return

		setMedia(stream)
	}

	override fun replaceItem(item: QueueEntry) {
		setMedia(requireNotNull(item.mediaStream))
	}

	override fun setBufferOptions(options: PlaybackBufferOptions) {
		normalBufferDuration = options.bufferForPlaybackDuration
		liveTvBufferDuration = options.liveTvBufferDuration
		maxBufferBytes = options.maxBufferBytes
	}

	override fun onActivated() {
		videoDecoderProvider?.invoke()?.let(::setVideoDecoder)
		playbackOptionsProvider?.invoke()?.let(::setPlaybackOptions)
	}

	private fun setMedia(stream: PlayableMediaStream) {
		if (released) return
		val generation = mediaGeneration.incrementAndGet()
		viewAttachScheduler.cancel()
		// Changing media must not tear down the surfaces under its running decoder.
		mediaReady = false
		inputStarted = false
		playRequested = true
		player.setEventListener(null)
		handler.removeCallbacks(tick)
		surroundBoostAllowed = false
		setSurroundBoostGain(0)
		playbackOptionsProvider?.invoke()?.let(::setPlaybackOptions)
		videoOutput.apply(VideoOutputTransform.NONE)
		listener?.onVideoGeometryChange(VideoGeometry.EMPTY)
		ensureInstanceOptions()
		val decoderSession = (++decoderSessionCounter).toString()
		currentStream = stream
		stream.errorOrigin?.activate()
		endReported = false
		playbackActive = false
		bufferingPercent = 0f
		observedAudioOutput = null
		bufferingReceived = false
		videoOutputCount = 0
		previousStatsSample = null
		pendingStartPosition = stream.queueEntry.startPosition?.takeIf { it > Duration.ZERO }
		initialSeekFailed = false
		initialPositionForReporting = pendingStartPosition
		startPlayWhenReady = true
		pendingInitialTrackTypes.clear()
		if (stream.conversionMethod == MediaConversionMethod.None) {
			if (stream.selectedAudioStreamIndex != null) pendingInitialTrackTypes += TrackType.AUDIO
			if (stream.selectedSubtitleStreamIndex != null) pendingInitialTrackTypes += TrackType.SUBTITLE
		}
		lastTickPosition = Duration.ZERO
		listener?.onSubtitleTimingOffsetSupportChange(true)
		listener?.onPlayStateChange(PlayState.BUFFERING)
		val media = Media(libVLC, Uri.parse(stream.url))
		try {
			media.setVideoDecoder(effectiveVideoDecoder)
			libVLCMediaOptions(
				isLiveTv = stream.queueEntry.isLiveTv,
				normalBufferDuration = normalBufferDuration,
				liveTvBufferDuration = liveTvBufferDuration,
				maxBufferBytes = maxBufferBytes,
				bitrate = stream.totalBitrate(),
				options = playbackOptions,
				subtitlesDisabled = stream.selectedSubtitleStreamIndex == -1,
			).forEach(media::addOption)
		} catch (error: Exception) {
			media.release()
			throw error
		}
		val target = player
		stoppingPlayers += target
		nativeExecutor.execute {
			try {
				if (generation != mediaGeneration.get()) return@execute
				if (target.hasMedia()) target.stop()
				detachStoppedViews(target, releasing = false)
				if (generation != mediaGeneration.get()) return@execute
				target.media = media
				if (generation != mediaGeneration.get()) return@execute
				// Mark only the new input's output; the previous item must not decide its surround gain.
				decoderLogReader.beginSession(decoderSession)
				stream.externalSubtitles.forEach { subtitle ->
					val select = shouldSelectExternalSubtitle(subtitle, stream.selectedSubtitleStreamIndex)
					target.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(subtitle.url), select)
				}
				handler.post {
					// A channel change or Stop may have happened while VLC was loading this media.
					if (generation != mediaGeneration.get() || released) return@post
					mediaReady = true
					target.rate = playbackSpeed
					videoOutput.reapply()
					target.setEventListener { event ->
						if (!released && generation == mediaGeneration.get() && target === player) onPlayerEvent(event)
					}
					viewAttachScheduler.cancel()
					attachViewsNow()
					startPlaybackIfReady()
				}
			} catch (error: Exception) {
				Timber.e(error, "VLC media assignment failed")
				handler.post {
					if (generation != mediaGeneration.get() || released) return@post
					listener?.onPlaybackError(PlaybackError("LIBVLC_ERROR", origin = stream.errorOrigin))
					listener?.onPlayStateChange(PlayState.ERROR)
				}
			} finally {
				media.release()
			}
		}
	}

	override fun play() {
		initialSeekFailed = false
		startPlayWhenReady = true
		if (mediaReady && pendingStartPosition != null && !playRequested) return
		playRequested = true
		startPlaybackIfReady()
	}

	override fun pause() {
		startPlayWhenReady = false
		if (released) return
		if (inputStarted) player.pause()
		else listener?.onPlayStateChange(PlayState.PAUSED)
	}

	override fun stop() {
		if (released) return
		mediaGeneration.incrementAndGet()
		mediaReady = false
		inputStarted = false
		playRequested = false
		startPlayWhenReady = false
		player.setEventListener(null)
		surroundBoostAllowed = false
		setSurroundBoostGain(0)
		handler.removeCallbacks(tick)
		videoOutput.apply(VideoOutputTransform.NONE)
		listener?.onVideoGeometryChange(VideoGeometry.EMPTY)
		viewAttachScheduler.cancel()
		val target = player
		stoppingPlayers += target
		nativeExecutor.execute {
			target.stop()
			detachStoppedViews(target, releasing = false)
		}
		currentStream = null
		pendingStartPosition = null
		initialSeekFailed = false
		initialPositionForReporting = null
		endReported = false
		playbackActive = false
		previousStatsSample = null
		decoderLogReader.endSession()
		observedAudioOutput = null
		pendingInitialTrackTypes.clear()
		lastTickPosition = Duration.ZERO
		forcedVideoDecoder = null
		listener?.onSubtitleTimingOffsetSupportChange(false)
		listener?.onPlayStateChange(PlayState.STOPPED)
	}

	override fun reset() {
		if (!released) stop()
	}

	override fun cleanup() {
		if (released) return
		handler.removeCallbacks(tick)
		setListener(null)
		setSurfaceView(null)
		setSubtitleView(null)
	}

	override fun release() {
		if (released) return
		reset()
		cleanup()
		player.setEventListener(null)
		val oldPlayer = player
		val oldLibVLC = libVLC
		nativeExecutor.execute {
			try {
				detachStoppedViews(oldPlayer, releasing = true)
				oldPlayer.release()
			} finally { oldLibVLC.release() }
		}
		nativeExecutor.shutdown()
		decoderLogReader.close()
		released = true
	}

	private fun detachStoppedViews(target: MediaPlayer, releasing: Boolean) {
		// Only the native worker waits; Android view cleanup must finish on main before native release.
		val cleanup = FutureTask {
			if (releasing || released || target !== player || currentStream == null) target.vlcVout.detachViews()
			stoppingPlayers -= target
			if (attachedPlayer === target && !target.vlcVout.areViewsAttached()) {
				attachedPlayer = null
				attachedVideoView = null
				attachedSubtitleView = null
			}
			val removals = pendingSubtitleSurfaceRemovals.filter { it.first === target }
			removals.forEach { (_, surface) -> (surface.parent as? ViewGroup)?.removeView(surface) }
			pendingSubtitleSurfaceRemovals.removeAll(removals.toSet())
		}
		handler.post(cleanup)
		cleanup.get()
	}

	override fun seekTo(position: Duration): Boolean {
		if (released) return false
		// Keep the newest seek until startup finishes; the original resume point must not overwrite it.
		if (!inputStarted || pendingStartPosition != null) {
			pendingStartPosition = position
			initialPositionForReporting = position
			return true
		}
		val previous = getPositionInfo()
		if (player.setTime(position.inWholeMilliseconds) < 0) {
			Timber.w("libVLC rejected seek to %d ms", position.inWholeMilliseconds)
			return false
		}
		initialPositionForReporting = null
		timedEvents.advance(previous.active, position, previous.duration, natural = false)
		lastTickPosition = position
		return true
	}

	override fun setScrubbing(scrubbing: Boolean) = Unit

	override fun setSpeed(speed: Float) {
		playbackSpeed = speed
		if (mediaReady && !released) player.rate = speed
	}

	fun setVideoDecoder(decoder: LibVLCVideoDecoder) {
		videoDecoder = decoder
	}

	fun setPlaybackOptions(options: LibVLCPlaybackOptions) {
		playbackOptions = options
		surroundBoostRequest = null
		updateSurroundVolumeBoost()
	}

	override fun setForcedVideoDecoderOption(option: VideoDecoderOption?) {
		forcedVideoDecoder = option?.let { LibVLCVideoDecoder.valueOf(it.id) }
	}

	private fun LibVLCVideoDecoder.toOption() = videoDecoderOptions.firstOrNull { option -> option.id == name }

	private fun currentInstanceOptions(): LibVLCInstanceOptions {
		val options = instanceOptionsProvider()
		return subtitleStyle?.let { style ->
			options.copy(arguments = options.arguments + style.libVLCOptions())
		} ?: options
	}

	private fun createLibVLC(options: LibVLCInstanceOptions) =
		LibVLC(appContext, ArrayList(options.arguments))

	private fun createPlayer(libVLC: LibVLC, options: LibVLCInstanceOptions) =
		MediaPlayer(libVLC).apply {
			setVideoTitleDisplay(MediaPlayer.Position.Disable, 0)
			vlcVout.addCallback(this@LibVLCBackend)
			audioOutputChannels = null
			options.audioOutput?.let(::setAudioOutput)
			setAudioDigitalOutputEnabled(false)
			val usesAudioTrack = when (options.audioOutput) {
				null -> HWDecoderUtil.getAudioOutputFromDevice() != HWDecoderUtil.AudioOutput.OPENSLES
				else -> options.audioOutput in listOf("audiotrack", "android_audiotrack")
			}
			if (usesAudioTrack) {
					// shortcut: keep the working stereo route on devices that reject multichannel DIRECT output; revisit after device validation.
					if (setAudioOutputDevice("stereo")) audioOutputChannels = 2
					else Timber.w("VLC rejected the stereo audio output device")
			}
			rate = playbackSpeed
		}

	private fun ensureInstanceOptions() {
		val desiredOptions = currentInstanceOptions()
		if (desiredOptions == appliedInstanceOptions) return

		handler.removeCallbacks(tick)
		player.setEventListener(null)
		val oldPlayer = player
		val oldLibVLC = libVLC
		stoppingPlayers += oldPlayer
		nativeExecutor.execute {
			try {
				if (oldPlayer.hasMedia()) oldPlayer.stop()
				detachStoppedViews(oldPlayer, releasing = true)
				oldPlayer.release()
			} finally { oldLibVLC.release() }
		}

		appliedInstanceOptions = desiredOptions
		libVLC = createLibVLC(desiredOptions)
		player = createPlayer(libVLC, desiredOptions)
		surroundBoostGain = 0
		surroundBoostRequest = null
		surroundBoostAllowed = false
		videoOutput.reapply()
		updateViews()
	}

	override fun setSubtitleTiming(offset: Duration, speed: Float) {
		subtitleTimingOffset = offset
		applySubtitleTimingOffset()
		if (speed != 1f) Timber.w("libVLC does not support subtitle timing speed")
	}

	private fun applySubtitleTimingOffset() {
		if (released || !mediaReady) return
		if (!player.setSpuDelay(subtitleTimingOffset.inWholeMicroseconds)) {
			Timber.w("libVLC rejected subtitle timing offset")
		}
	}

	override fun setTimedEvents(timedEvents: List<TimedEvent>) {
		this.timedEvents.setEvents(timedEvents)
	}

	override fun getPositionInfo(): PositionInfo {
		if (released || !mediaReady) {
			val position = initialPositionForReporting ?: Duration.ZERO
			return PositionInfo(position, position, Duration.ZERO, isSeekable = false)
		}
		val nativePosition = player.time.coerceAtLeast(0).milliseconds
		// Playing can arrive before VLC publishes the timestamp of the initial seek.
		initialPositionForReporting?.let { initial ->
			if (nativePosition >= initial) initialPositionForReporting = null
		}
		val active = initialPositionForReporting ?: nativePosition
		val duration = player.length.coerceAtLeast(0).milliseconds
		return PositionInfo(active, active, duration)
	}

	override fun getFrameStats(): PlaybackFrameStats {
		if (released || !mediaReady) return PlaybackFrameStats(droppedFrames = 0, corruptedFrames = 0, playerName = "libVLC")
		val stats = player.media?.let { media ->
			try {
				media.stats
			} finally {
				media.release()
			}
		}
		val currentStatsSample = stats?.let {
			LibVLCStatsSample(
				decodedVideo = it.decodedVideo.toLong(),
				displayedPictures = it.displayedPictures.toLong(),
				elapsedRealtimeNanos = System.nanoTime(),
			)
		}
		val rates = currentStatsSample?.let { calculateLibVLCRates(previousStatsSample, it) }
		previousStatsSample = currentStatsSample
		val trackDiagnostics = libVLCTrackDiagnostics(
			video = player.currentVideoTrack,
			audio = mediaTracks().firstOrNull { it.type == IMedia.Track.Type.Audio && it.id == player.audioTrack } as? IMedia.AudioTrack,
		)
		val estimatedBytes = estimateBufferedBytes(
			stats?.demuxBitrate,
			if (currentStream?.queueEntry?.isLiveTv == true) liveTvBufferDuration else normalBufferDuration,
		)
		val bufferDetails = formatLibVLCBufferDetails(estimatedBytes, bufferingPercent)
		val decoderDiagnostics = decoderLogReader.snapshot()
		return PlaybackFrameStats(
			droppedFrames = stats?.lostPictures?.coerceAtLeast(0) ?: 0,
			corruptedFrames = stats?.demuxCorrupted?.coerceAtLeast(0) ?: 0,
			playerName = "libVLC",
			videoDecodedFrames = stats?.decodedVideo?.coerceAtLeast(0) ?: 0,
			videoDecoderFps = rates?.decodedFps,
			videoDecoderName = decoderDiagnostics.video?.name,
			videoDecoderType = decoderDiagnostics.video?.type,
			videoCodec = trackDiagnostics.videoCodec,
			videoSourceFps = trackDiagnostics.videoSourceFps,
			videoBitrate = trackDiagnostics.videoBitrate,
			audioDecoderName = decoderDiagnostics.audio?.name ?: "libVLC",
			audioDecoderType = decoderDiagnostics.audio?.type,
			audioCodec = trackDiagnostics.audioCodec,
			audioBitrate = trackDiagnostics.audioBitrate,
			audioChannels = trackDiagnostics.audioChannels,
			audioSampleRate = trackDiagnostics.audioSampleRate,
			bufferedBytes = bufferDetails,
			subtitleExtractor = "libVLC",
			subtitleRender = "libVLC",
			backendDetails = buildMap {
				(audioOutputChannels ?: observedAudioOutput?.channels)?.let { put("Audio output channels", it.toString()) }
				observedAudioOutput?.let { put("Audio output format", "${it.format} ${it.sampleRate} Hz") }
				put("Video outputs", videoOutputCount.toString())
				if (stats != null) putAll(libVLCBackendDetails(stats, rates))
				putAll(trackDiagnostics.details)
			},
		)
	}

	private fun onPlayerEvent(event: MediaPlayer.Event) {
		// A queued startup event must not hide a resume failure before the user retries.
		if (initialSeekFailed) return
		when (event.type) {
			MediaPlayer.Event.Opening -> {
				surroundBoostAllowed = false
				setSurroundBoostGain(0)
				bufferingPercent = 0f
				bufferingReceived = false
				playbackActive = false
				listener?.onPlayStateChange(PlayState.BUFFERING)
			}
			MediaPlayer.Event.Buffering -> {
				bufferingReceived = true
				bufferingPercent = normalizeBufferingPercent(event.buffering, bufferingPercent)
				publishPlaybackState()
			}
			MediaPlayer.Event.SeekableChanged -> {
				if (event.seekable && pendingStartPosition != null && !player.isPlaying) applyPendingStartPosition()
			}
			MediaPlayer.Event.Vout -> {
				videoOutputCount = event.voutCount
				if (videoOutputCount > 0) player.updateVideoSurfaces()
				publishPlaybackState()
			}
			MediaPlayer.Event.ESAdded -> when (event.esChangedType) {
				IMedia.Track.Type.Audio -> {
					notifyTracksChanged()
					applyInitialTrackSelection(TrackType.AUDIO)
					updateSurroundVolumeBoost()
				}
				IMedia.Track.Type.Text -> {
					notifyTracksChanged()
					applyInitialTrackSelection(TrackType.SUBTITLE)
				}
				else -> Unit
			}
			MediaPlayer.Event.ESDeleted,
			MediaPlayer.Event.ESSelected,
			-> when (event.esChangedType) {
				IMedia.Track.Type.Audio -> {
					notifyTracksChanged()
					surroundBoostRequest = null
					updateSurroundVolumeBoost()
				}
				IMedia.Track.Type.Text -> notifyTracksChanged()
				else -> Unit
			}
			MediaPlayer.Event.Playing -> {
				if (!startPlayWhenReady) {
					if (player.isPlaying) player.pause()
					return
				}
				surroundBoostAllowed = true
				playbackActive = true
				applyInitialTrackSelection()
				updateSurroundVolumeBoost()
				applySubtitleTimingOffset()
				handler.removeCallbacks(tick)
				lastTickPosition = getPositionInfo().active
				handler.post(tick)
				publishPlaybackState()
			}
			MediaPlayer.Event.Paused -> {
				if (applyPendingStartPosition()) return
				playbackActive = false
				handler.removeCallbacks(tick)
				listener?.onPlayStateChange(PlayState.PAUSED)
			}
			MediaPlayer.Event.Stopped -> {
				surroundBoostAllowed = false
				setSurroundBoostGain(0)
				playbackActive = false
				handler.removeCallbacks(tick)
				listener?.onPlayStateChange(PlayState.STOPPED)
			}
			MediaPlayer.Event.EndReached -> {
				surroundBoostAllowed = false
				setSurroundBoostGain(0)
				playbackActive = false
				handler.removeCallbacks(tick)
				listener?.onPlayStateChange(PlayState.STOPPED)
				if (!endReported) currentStream?.let { listener?.onMediaStreamEnd(it) }
				endReported = true
			}
			MediaPlayer.Event.EncounteredError -> {
				surroundBoostAllowed = false
				setSurroundBoostGain(0)
				playbackActive = false
				handler.removeCallbacks(tick)
				listener?.onPlaybackError(PlaybackError("LIBVLC_ERROR", origin = currentStream?.errorOrigin))
				listener?.onPlayStateChange(PlayState.ERROR)
			}
		}
	}

	private fun publishPlaybackState() {
		if (!playbackActive || !startPlayWhenReady) return
		val needsVideo = currentStream?.tracks?.any { it is MediaStreamVideoTrack } == true
		// Local files may never report buffering; wait for video output without inventing a buffer stall.
		val state = bufferingPlayState(
			percent = if (bufferingReceived) bufferingPercent else 100f,
			playbackActive = playbackActive,
			waitingForVideo = needsVideo && videoOutputCount == 0,
		)
		state?.let { listener?.onPlayStateChange(it) }
	}

	private fun applyPendingStartPosition(): Boolean {
		val position = pendingStartPosition ?: return false
		val result = player.setTime(position.inWholeMilliseconds)
		Timber.i("VLC initial seek requestedMs=%d seekable=%s result=%d", position.inWholeMilliseconds, player.isSeekable, result)
		if (result < 0) {
			initialSeekFailed = true
			// Do not hide a rejected resume behind a permanent pending seek or silently play from zero.
			pendingStartPosition = null
			initialPositionForReporting = null
			startPlayWhenReady = false
			playbackActive = false
			handler.removeCallbacks(tick)
			listener?.onPlayStateChange(PlayState.ERROR)
			listener?.onPlaybackError(PlaybackError("LIBVLC_INITIAL_SEEK_FAILED", origin = currentStream?.errorOrigin))
			return true
		}
		pendingStartPosition = null
		lastTickPosition = position
		if (!startPlayWhenReady) return false
		player.play()
		return true
	}

	private fun applyInitialTrackSelection(type: TrackType? = null) {
		val stream = currentStream ?: return
		pendingInitialTrackTypes.toList().forEach { pendingType ->
			if (type != null && type != pendingType) return@forEach
			val streamIndex = when (pendingType) {
				TrackType.AUDIO -> stream.selectedAudioStreamIndex
				TrackType.SUBTITLE -> stream.selectedSubtitleStreamIndex
			} ?: return@forEach

			if (applyInitialTrackSelection(stream, pendingType, streamIndex)) {
				pendingInitialTrackTypes -= pendingType
			}
		}
	}

	private fun applyInitialTrackSelection(
		stream: PlayableMediaStream,
		type: TrackType,
		streamIndex: Int,
	): Boolean {
		if (type == TrackType.SUBTITLE && streamIndex < 0) {
			return player.spuTrack == -1 || player.setSpuTrack(-1)
		}
		val sourceIndex = stream.sourceTrackIndex(type, streamIndex)
		if (sourceIndex == null) {
			Timber.w("Could not find initial %s stream index %d", type.name.lowercase(), streamIndex)
			return true
		}
		val track = selectableTracks(type).getOrNull(sourceIndex) ?: return false
		val selected = when (type) {
			TrackType.AUDIO -> player.audioTrack == track.id || player.setAudioTrack(track.id)
			TrackType.SUBTITLE -> player.spuTrack == track.id || player.setSpuTrack(track.id)
		}
		if (selected) {
			Timber.i("Applied initial %s stream index %d as libVLC track %s", type.name.lowercase(), streamIndex, track.id)
		}
		return selected
	}

	private fun selectableTracks(type: TrackType, mediaIds: List<Int> = mediaTrackIds(type)): List<MediaPlayer.TrackDescription> {
		val descriptions = when (type) {
			TrackType.AUDIO -> player.audioTracks
			TrackType.SUBTITLE -> player.spuTracks
		}.orEmpty()
		val descriptionsById = descriptions.associateBy { it.id }
		return orderedLibVLCTrackIds(mediaIds, descriptions.map { it.id })
			.mapNotNull(descriptionsById::get)
	}

	private fun mediaTrackIds(type: TrackType): List<Int> = mediaTracks()
		.filter { it.type == type.libVLCTrackType() }.map { it.id }

	private fun mediaTracks(): List<IMedia.Track> {
		if (released || !mediaReady) return emptyList()
		val media = player.media ?: return emptyList()
		return try {
			(0 until media.trackCount).mapNotNull(media::getTrack)
		} finally {
			media.release()
		}
	}

	override fun onNewVideoLayout(
		vout: IVLCVout,
		width: Int,
		height: Int,
		visibleWidth: Int,
		visibleHeight: Int,
		sarNum: Int,
		sarDen: Int,
	) {
		if (released || !mediaReady || vout !== player.vlcVout) return
		videoSurfaceWidthScale = if (width > 0 && visibleWidth > 0) width.toDouble() / visibleWidth else 1.0
		videoSurfaceHeightScale = if (height > 0 && visibleHeight > 0) height.toDouble() / visibleHeight else 1.0
		updateVideoWindowSize()
		listener?.onVideoGeometryChange(libVLCVideoGeometry(width, height, visibleWidth, visibleHeight, sarNum, sarDen))
	}

	override fun getAvailableTracks(type: TrackType): List<PlayerTrack> {
		if (released || !mediaReady) return emptyList()
		val mediaIds = mediaTrackIds(type)
		val sourceTracks = currentStream?.libVLCSourceTracks(type, mediaIds.size).orEmpty()
		val tracks = selectableTracks(type, mediaIds)
		val metadata = mediaTracks().filter { it.type == type.libVLCTrackType() }.associateBy { it.id }
		val selectedId = when (type) {
			TrackType.AUDIO -> player.audioTrack
			TrackType.SUBTITLE -> player.spuTrack
		}
		return tracks.mapIndexed { index, track ->
			libVLCPlayerTrack(index, type, track.id, track.name, metadata[track.id], selectedId, sourceTracks.getOrNull(index))
		}
	}

	override fun selectTrack(type: TrackType, index: Int): Boolean {
		if (released || !mediaReady) return false
		pendingInitialTrackTypes -= type
		val stream = currentStream ?: return false
		if (type == TrackType.SUBTITLE && index == -1) {
			if (!stream.allowsLocalSubtitleDisable()) return false
			return player.setSpuTrack(-1)
		}
		val mediaIds = mediaTrackIds(type)
		val track = selectableTracks(type, mediaIds).getOrNull(index) ?: return false
		val sourceTrack = stream.libVLCSourceTracks(type, mediaIds.size).getOrNull(index)
		if (!stream.allowsLocalTrackSelection(type, sourceTrack)) return false
		return when (type) {
			TrackType.AUDIO -> player.setAudioTrack(track.id)
			TrackType.SUBTITLE -> player.setSpuTrack(track.id)
		}
	}
}

private fun TrackType.libVLCTrackType() = when (this) {
	TrackType.AUDIO -> IMedia.Track.Type.Audio
	TrackType.SUBTITLE -> IMedia.Track.Type.Text
}

internal fun bufferingPlayState(percent: Float, playbackActive: Boolean, waitingForVideo: Boolean = false): PlayState? = when {
	!playbackActive -> null
	waitingForVideo -> PlayState.BUFFERING
	percent < 100f -> PlayState.BUFFERING
	else -> PlayState.PLAYING
}

internal fun libVLCPlayerTrack(
	index: Int,
	type: TrackType,
	trackId: Int,
	trackName: String?,
	metadata: IMedia.Track?,
	selectedId: Int,
	source: MediaStreamTrack?,
) = PlayerTrack(
	index = index,
	type = type,
	label = trackName ?: when (source) {
		is MediaStreamAudioTrack -> source.title
		is MediaStreamSubtitleTrack -> source.title
		else -> null
	},
	language = metadata?.language ?: when (source) {
		is MediaStreamAudioTrack -> source.language
		is MediaStreamSubtitleTrack -> source.language
		else -> null
	},
	codec = metadata?.codec ?: source?.codec,
	isSelected = trackId == selectedId,
	streamIndex = source?.index,
	trackIndex = index,
)

internal fun estimateBufferedBytes(bytesPerSecond: Float?, duration: Duration?): Long? {
	if (bytesPerSecond == null || !bytesPerSecond.isFinite() || bytesPerSecond <= 0f || duration == null || duration <= Duration.ZERO) return null
	return (bytesPerSecond * duration.inWholeMilliseconds / 1_000.0).toLong()
}

internal fun normalizeBufferingPercent(percent: Float, fallback: Float): Float =
	percent.takeIf(Float::isFinite)?.coerceIn(0f, 100f) ?: fallback

internal fun formatLibVLCBufferDetails(estimatedBytes: Long?, bufferingPercent: Float) = buildList {
	estimatedBytes?.let { add("~${it.formatBufferBytes()}") }
	if (bufferingPercent < 100f) add("buffering ${bufferingPercent.toInt()}%")
}.joinToString(", ").takeIf(String::isNotEmpty)

internal data class LibVLCStatsSample(
	val decodedVideo: Long,
	val displayedPictures: Long,
	val elapsedRealtimeNanos: Long,
)

internal data class LibVLCRates(
	val decodedFps: Float,
	val displayedFps: Float,
)

internal fun calculateLibVLCRates(
	previous: LibVLCStatsSample?,
	current: LibVLCStatsSample,
): LibVLCRates? {
	previous ?: return null
	val elapsedNanos = current.elapsedRealtimeNanos - previous.elapsedRealtimeNanos
	val decoded = current.decodedVideo - previous.decodedVideo
	val displayed = current.displayedPictures - previous.displayedPictures
	if (elapsedNanos <= 0 || decoded < 0 || displayed < 0) return null

	val elapsedSeconds = elapsedNanos / 1_000_000_000f
	return LibVLCRates(
		decodedFps = decoded / elapsedSeconds,
		displayedFps = displayed / elapsedSeconds,
	)
}

internal fun libVLCBackendDetails(
	stats: IMedia.Stats,
	rates: LibVLCRates?,
): Map<String, String> = buildMap {
	put("Input", "${stats.readBytes.nonNegativeBytes()} read, ${stats.inputBitrate.byteRate()}")
	put("Demux", "${stats.demuxReadBytes.nonNegativeBytes()} read, ${stats.demuxBitrate.byteRate()}")
	put("Video pictures", "${stats.decodedVideo} decoded, ${stats.displayedPictures} displayed")
	rates?.let {
		put("Video rate", "${it.decodedFps.rate()} decoded/s, ${it.displayedFps.rate()} displayed/s")
	}
	put("Audio buffers", "${stats.decodedAudio} decoded, ${stats.playedAbuffers} played, ${stats.lostAbuffers} lost")
	put("Demux discontinuities", stats.demuxDiscontinuity.toString())
	if (stats.sentPackets > 0 || stats.sendBitrate > 0f) {
		put("Stream output", "${stats.sentPackets} packets, ${stats.sentBytes.nonNegativeBytes()}, ${stats.sendBitrate.byteRate()}")
	}
}

internal data class LibVLCTrackDiagnostics(
	val videoCodec: String? = null,
	val videoBitrate: Int? = null,
	val videoSourceFps: Float? = null,
	val audioCodec: String? = null,
	val audioBitrate: Int? = null,
	val audioChannels: String? = null,
	val audioSampleRate: Int? = null,
	val details: Map<String, String> = emptyMap(),
)

internal fun libVLCTrackDiagnostics(
	video: IMedia.VideoTrack?,
	audio: IMedia.AudioTrack?,
): LibVLCTrackDiagnostics {
	val sourceFps = if (video != null && video.frameRateNum > 0 && video.frameRateDen > 0) {
		video.frameRateNum.toFloat() / video.frameRateDen
	} else null

	return LibVLCTrackDiagnostics(
		videoCodec = video?.codec?.takeUnless(String::isBlank),
		videoBitrate = video?.bitrate?.takeIf { it > 0 },
		videoSourceFps = sourceFps,
		audioCodec = audio?.codec?.takeUnless(String::isBlank),
		audioBitrate = audio?.bitrate?.takeIf { it > 0 },
		audioChannels = audio?.channels?.takeIf { it > 0 }?.toString(),
		audioSampleRate = audio?.rate?.takeIf { it > 0 },
		details = buildMap {
			video?.profile?.takeIf { it > 0 }?.let { put("Video profile", it.toString()) }
			video?.level?.takeIf { it > 0 }?.let { put("Video level", it.toString()) }
		},
	)
}

private fun Int.nonNegativeBytes() = coerceAtLeast(0).toLong().formatBufferBytes()

private fun Float.byteRate() = takeIf { it.isFinite() && it > 0f }
	?.toLong()
	?.formatBufferBytes()
	?.let { "$it/s" }
	?: "0 B/s"

private fun Float.rate() = String.format(Locale.US, "%.3f", this)
