package org.jellyfin.playback.libvlc

import android.content.Context
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.jellyfin.playback.core.PlaybackBufferOptions
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
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
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
import timber.log.Timber
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class LibVLCVideoDecoder(val vlcValue: Int, val label: String) {
	AUTOMATIC(-1, "Auto"),
	DISABLED(0, "Software"),
	DECODING(1, "Decoding"),
	FULL(2, "Full"),
}

data class LibVLCPlaybackOptions(
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
): List<String> = buildList {
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

internal fun PlayableMediaStream.libVLCSourceTracks(type: TrackType): List<MediaStreamTrack> = when (type) {
	TrackType.AUDIO -> tracks.filterIsInstance<MediaStreamAudioTrack>()
	TrackType.SUBTITLE -> {
		val subtitles = tracks.filterIsInstance<MediaStreamSubtitleTrack>()
		subtitles.filterNot { it.isExternal } + externalSubtitles.mapNotNull { external ->
			subtitles.firstOrNull { subtitle -> subtitle.isExternal && subtitle.index == external.index }
		}
	}
}

internal fun PlayableMediaStream.sourceTrackIndex(type: TrackType, streamIndex: Int): Int? =
	libVLCSourceTracks(type)
		.indexOfFirst { track -> track.index == streamIndex }
		.takeIf { index -> index >= 0 }

internal fun orderedLibVLCTrackIds(
	mediaTrackIds: List<String>,
	descriptionTrackIds: List<String>,
): List<String> {
	val selectableIds = descriptionTrackIds.filter(String::isNotEmpty).distinct()
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
) : BasePlayerBackend(), TrackSelectionBackend, IVLCVout.OnNewVideoLayoutListener {
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
	private var playbackActive = false
	private var playbackSpeed = 1f
	private var appliedInstanceOptions = currentInstanceOptions()
	private var libVLC = createLibVLC(appliedInstanceOptions)
	private var player = createPlayer(libVLC, appliedInstanceOptions)
	private val videoOutput = LibVLCVideoOutput { aspectRatio -> player.setAspectRatio(aspectRatio) }
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
	private var currentStream: PlayableMediaStream? = null
	private var surfaceView: PlayerSurfaceView? = null
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

	override fun supportsStream(stream: MediaStream): PlaySupportReport = object : PlaySupportReport {
		override val canPlay = true
	}

	override fun setSurfaceView(surfaceView: PlayerSurfaceView?) {
		this.surfaceView = surfaceView
		updateViews()
	}

	override fun setSubtitleView(surfaceView: PlayerSubtitleView?) {
		subtitleView?.onSubtitleStyleChanged = null
		subtitleLayoutListener?.let { listener -> subtitleView?.removeOnLayoutChangeListener(listener) }
		subtitleLayoutListener = null
		(subtitleSurface?.parent as? ViewGroup)?.removeView(subtitleSurface)
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
			player.vlcVout.detachViews()
		} else {
			viewAttachScheduler.schedule()
		}
	}

	private fun attachViewsNow() {
		player.vlcVout.detachViews()
		val video = surfaceView?.surface ?: return
		player.vlcVout.setVideoView(video)
		subtitleSurface?.let(player.vlcVout::setSubtitlesView)
		player.vlcVout.attachViews(this)
	}

	override fun prepareItem(item: QueueEntry) = Unit

	override fun playItem(item: QueueEntry) {
		val stream = requireNotNull(item.mediaStream)
		if (stream == currentStream && player.isPlaying) return

		setMedia(stream)
		player.play()
	}

	override fun replaceItem(item: QueueEntry) {
		setMedia(requireNotNull(item.mediaStream))
		player.play()
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
		videoOutput.apply(VideoOutputTransform.NONE)
		listener?.onVideoGeometryChange(VideoGeometry.EMPTY)
		ensureInstanceOptions()
		currentStream = stream
		stream.errorOrigin?.activate()
		endReported = false
		playbackActive = false
		pendingInitialTrackTypes.clear()
		if (stream.conversionMethod == MediaConversionMethod.None) {
			if (stream.selectedAudioStreamIndex != null) pendingInitialTrackTypes += TrackType.AUDIO
			if (stream.selectedSubtitleStreamIndex != null) pendingInitialTrackTypes += TrackType.SUBTITLE
		}
		lastTickPosition = Duration.ZERO
		listener?.onSubtitleTimingOffsetSupportChange(true)
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
			).forEach(media::addOption)
			player.media = media
		} finally {
			media.release()
		}
		stream.externalSubtitles.forEach { subtitle ->
			val select = shouldSelectExternalSubtitle(subtitle, stream.selectedSubtitleStreamIndex)
			player.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(subtitle.url), select)
		}
	}

	override fun play() {
		player.play()
	}

	override fun pause() {
		player.pause()
	}

	override fun stop() {
		handler.removeCallbacks(tick)
		videoOutput.apply(VideoOutputTransform.NONE)
		listener?.onVideoGeometryChange(VideoGeometry.EMPTY)
		player.stop()
		currentStream = null
		endReported = false
		playbackActive = false
		pendingInitialTrackTypes.clear()
		lastTickPosition = Duration.ZERO
		forcedVideoDecoder = null
		listener?.onSubtitleTimingOffsetSupportChange(false)
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
		player.release()
		libVLC.release()
		released = true
	}

	override fun seekTo(position: Duration): Boolean {
		val previous = getPositionInfo()
		if (player.setTime(position.inWholeMilliseconds) < 0) {
			Timber.w("libVLC rejected seek to %d ms", position.inWholeMilliseconds)
			return false
		}
		timedEvents.advance(previous.active, position, previous.duration, natural = false)
		lastTickPosition = position
		return true
	}

	override fun setScrubbing(scrubbing: Boolean) = Unit

	override fun setSpeed(speed: Float) {
		playbackSpeed = speed
		player.rate = speed
	}

	fun setVideoDecoder(decoder: LibVLCVideoDecoder) {
		videoDecoder = decoder
	}

	fun setPlaybackOptions(options: LibVLCPlaybackOptions) {
		playbackOptions = options
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
			options.audioOutput?.let(::setAudioOutput)
			setEventListener(::onPlayerEvent)
			rate = playbackSpeed
		}

	private fun ensureInstanceOptions() {
		val desiredOptions = currentInstanceOptions()
		if (desiredOptions == appliedInstanceOptions) return

		handler.removeCallbacks(tick)
		player.vlcVout.detachViews()
		player.setEventListener(null)
		player.release()
		libVLC.release()

		appliedInstanceOptions = desiredOptions
		libVLC = createLibVLC(desiredOptions)
		player = createPlayer(libVLC, desiredOptions)
		videoOutput.reapply()
		updateViews()
	}

	override fun setSubtitleTiming(offset: Duration, speed: Float) {
		subtitleTimingOffset = offset
		applySubtitleTimingOffset()
		if (speed != 1f) Timber.w("libVLC does not support subtitle timing speed")
	}

	private fun applySubtitleTimingOffset() {
		if (!player.setSpuDelay(subtitleTimingOffset.inWholeMicroseconds)) {
			Timber.w("libVLC rejected subtitle timing offset")
		}
	}

	override fun setTimedEvents(timedEvents: List<TimedEvent>) {
		this.timedEvents.setEvents(timedEvents)
	}

	override fun getPositionInfo(): PositionInfo {
		val active = player.time.coerceAtLeast(0).milliseconds
		val duration = player.length.coerceAtLeast(0).milliseconds
		return PositionInfo(active, active, duration)
	}

	override fun getFrameStats(): PlaybackFrameStats {
		val stats = player.media?.let { media ->
			try {
				media.stats
			} finally {
				media.release()
			}
		}
		val estimatedBytes = estimateBufferedBytes(
			stats?.demuxBitrate,
			if (currentStream?.queueEntry?.isLiveTv == true) liveTvBufferDuration else normalBufferDuration,
		)
		val bufferDetails = formatLibVLCBufferDetails(estimatedBytes, bufferingPercent)
		return PlaybackFrameStats(
			droppedFrames = stats?.lostPictures?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
			corruptedFrames = stats?.demuxCorrupted?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
			playerName = "libVLC",
			videoDecodedFrames = stats?.decodedVideo?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
			videoDecoderName = "libVLC ${effectiveVideoDecoder.label}",
			audioDecoderName = "libVLC",
			bufferedBytes = bufferDetails,
			subtitleExtractor = "libVLC",
			subtitleRender = "libVLC",
		)
	}

	private fun onPlayerEvent(event: MediaPlayer.Event) {
		when (event.type) {
			MediaPlayer.Event.Opening -> {
				bufferingPercent = 0f
				playbackActive = false
				listener?.onPlayStateChange(PlayState.BUFFERING)
			}
			MediaPlayer.Event.Buffering -> {
				bufferingPercent = normalizeBufferingPercent(event.buffering, bufferingPercent)
				bufferingPlayState(bufferingPercent, playbackActive, player.isPlaying)
					?.let { listener?.onPlayStateChange(it) }
			}
			MediaPlayer.Event.ESAdded -> when (event.esChangedType) {
				IMedia.Track.Type.Audio -> {
					notifyTracksChanged()
					applyInitialTrackSelection(TrackType.AUDIO)
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
				IMedia.Track.Type.Audio,
				IMedia.Track.Type.Text,
				-> notifyTracksChanged()
				else -> Unit
			}
			MediaPlayer.Event.Playing -> {
				bufferingPercent = 100f
				playbackActive = true
				applyInitialTrackSelection()
				applySubtitleTimingOffset()
				handler.removeCallbacks(tick)
				lastTickPosition = getPositionInfo().active
				handler.post(tick)
				listener?.onPlayStateChange(PlayState.PLAYING)
			}
			MediaPlayer.Event.Paused -> {
				playbackActive = false
				handler.removeCallbacks(tick)
				listener?.onPlayStateChange(PlayState.PAUSED)
			}
			MediaPlayer.Event.Stopped -> {
				playbackActive = false
				handler.removeCallbacks(tick)
				listener?.onPlayStateChange(PlayState.STOPPED)
			}
			MediaPlayer.Event.EndReached -> {
				playbackActive = false
				handler.removeCallbacks(tick)
				listener?.onPlayStateChange(PlayState.STOPPED)
				if (!endReported) currentStream?.let { listener?.onMediaStreamEnd(it) }
				endReported = true
			}
			MediaPlayer.Event.EncounteredError -> {
				playbackActive = false
				handler.removeCallbacks(tick)
				listener?.onPlaybackError(PlaybackError("LIBVLC_ERROR", origin = currentStream?.errorOrigin))
				listener?.onPlayStateChange(PlayState.ERROR)
			}
		}
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
			if (player.getSelectedTrack(IMedia.Track.Type.Text) == null) return true
			player.unselectTrackType(IMedia.Track.Type.Text)
			return true
		}
		val sourceIndex = stream.sourceTrackIndex(type, streamIndex)
		if (sourceIndex == null) {
			Timber.w("Could not find initial %s stream index %d", type.name.lowercase(), streamIndex)
			return true
		}
		val track = selectableTracks(type).getOrNull(sourceIndex) ?: return false
		val selected = when (type) {
			TrackType.AUDIO -> player.getSelectedTrack(IMedia.Track.Type.Audio)?.id == track.id || player.selectTrack(track.id)
			TrackType.SUBTITLE -> player.getSelectedTrack(IMedia.Track.Type.Text)?.id == track.id || player.selectTrack(track.id)
		}
		if (selected) {
			Timber.i("Applied initial %s stream index %d as libVLC track %s", type.name.lowercase(), streamIndex, track.id)
		}
		return selected
	}

	private fun selectableTracks(type: TrackType): List<IMedia.Track> {
		val vlcType = type.libVLCTrackType()
		val descriptions = player.getTracks(vlcType).orEmpty()
		val descriptionsById = descriptions.associateBy(IMedia.Track::id)
		return orderedLibVLCTrackIds(mediaTrackIds(type), descriptions.map(IMedia.Track::id))
			.mapNotNull(descriptionsById::get)
	}

	private fun mediaTrackIds(type: TrackType): List<String> {
		val media = player.media ?: return emptyList()
		return try {
			media.getTracks(type.libVLCTrackType()).orEmpty().map(IMedia.Track::id)
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
		listener?.onVideoGeometryChange(
			libVLCVideoGeometry(width, height, visibleWidth, visibleHeight, sarNum, sarDen)
		)
	}

	override fun getAvailableTracks(type: TrackType): List<PlayerTrack> {
		val sourceTracks = currentStream?.libVLCSourceTracks(type).orEmpty()
		val tracks = selectableTracks(type)
		return tracks.mapIndexed { index, track ->
			libVLCPlayerTrack(index, type, track, sourceTracks.getOrNull(index))
		}
	}

	override fun selectTrack(type: TrackType, index: Int): Boolean {
		pendingInitialTrackTypes -= type
		if (currentStream?.conversionMethod != MediaConversionMethod.None) return false
		if (type == TrackType.SUBTITLE && index == -1) {
			player.unselectTrackType(IMedia.Track.Type.Text)
			return true
		}
		val track = selectableTracks(type).getOrNull(index) ?: return false
		return player.selectTrack(track.id)
	}
}

private fun TrackType.libVLCTrackType() = when (this) {
	TrackType.AUDIO -> IMedia.Track.Type.Audio
	TrackType.SUBTITLE -> IMedia.Track.Type.Text
}

internal fun bufferingPlayState(percent: Float, playbackActive: Boolean, playerIsPlaying: Boolean): PlayState? = when {
	!playbackActive -> null
	percent < 100f && playerIsPlaying -> null
	percent < 100f -> PlayState.BUFFERING
	else -> PlayState.PLAYING
}

internal fun libVLCPlayerTrack(
	index: Int,
	type: TrackType,
	track: IMedia.Track,
	source: MediaStreamTrack?,
) = PlayerTrack(
	index = index,
	type = type,
	label = track.name ?: when (source) {
		is MediaStreamAudioTrack -> source.title
		is MediaStreamSubtitleTrack -> source.title
		else -> null
	},
	language = track.language ?: when (source) {
		is MediaStreamAudioTrack -> source.language
		is MediaStreamSubtitleTrack -> source.language
		else -> null
	},
	codec = track.codec ?: source?.codec,
	isSelected = track.selected,
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
