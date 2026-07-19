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
import org.jellyfin.playback.core.backend.BasePlayerBackend
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.PlayerTrack
import org.jellyfin.playback.core.backend.TrackSelectionBackend
import org.jellyfin.playback.core.backend.TrackType
import org.jellyfin.playback.core.backend.VideoDecoderOption
import org.jellyfin.playback.core.mediastream.ExternalSubtitle
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.core.mediastream.MediaStreamTrack
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PlaybackFrameStats
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.isLiveTv
import org.jellyfin.playback.core.support.PlaySupportReport
import org.jellyfin.playback.core.timedevent.TimedEvent
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

enum class LibVlcVideoDecoder(val vlcValue: Int) {
	AUTOMATIC(-1),
	DISABLED(0),
	DECODING(1),
	FULL(2),
}

data class LibVlcPlaybackOptions(
	val deblocking: Int = -1,
	val frameSkip: Boolean = false,
	val audioTimeStretch: Boolean = false,
	val dav1dThreadFrames: Int = 0,
)

data class LibVlcInstanceOptions(
	val arguments: List<String> = emptyList(),
	val audioOutput: String? = null,
)

internal fun Media.setVideoDecoder(decoder: LibVlcVideoDecoder) {
	when (decoder) {
		LibVlcVideoDecoder.AUTOMATIC -> Unit
		LibVlcVideoDecoder.DISABLED -> setHWDecoderEnabled(false, false)
		LibVlcVideoDecoder.DECODING -> {
			setHWDecoderEnabled(true, true)
			addOption(":no-mediacodec-dr")
			addOption(":no-omxil-dr")
		}
		LibVlcVideoDecoder.FULL -> setHWDecoderEnabled(true, true)
	}
}

internal fun libVlcMediaOptions(
	isLiveTv: Boolean,
	liveTvBufferDuration: Duration?,
	options: LibVlcPlaybackOptions,
): List<String> = buildList {
	add(if (options.audioTimeStretch) ":audio-time-stretch" else ":no-audio-time-stretch")
	add(":avcodec-skiploopfilter=${resolveDeblocking(options.deblocking)}")
	add(":avcodec-skip-frame=${if (options.frameSkip) 2 else 0}")
	add(":avcodec-skip-idct=${if (options.frameSkip) 2 else 0}")
	add(":stats")
	add(":audio-resampler=soxr")
	if (options.dav1dThreadFrames >= 1) add(":dav1d-thread-frames=${options.dav1dThreadFrames}")

	val networkCachingMs = liveTvBufferDuration?.inWholeMilliseconds?.takeIf { isLiveTv }
	if (networkCachingMs != null) {
		add(":network-caching=$networkCachingMs")
	}
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

internal fun PlayerSubtitleStyle.libVlcOptions() = buildList {
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

internal fun PlayableMediaStream.libVlcSourceTracks(type: TrackType): List<MediaStreamTrack> = when (type) {
	TrackType.AUDIO -> tracks.filterIsInstance<MediaStreamAudioTrack>()
	TrackType.SUBTITLE -> {
		val subtitles = tracks.filterIsInstance<MediaStreamSubtitleTrack>()
		subtitles.filterNot { it.isExternal } + externalSubtitles.mapNotNull { external ->
			subtitles.firstOrNull { subtitle -> subtitle.isExternal && subtitle.index == external.index }
		}
	}
}

internal fun PlayableMediaStream.sourceTrackIndex(type: TrackType, streamIndex: Int): Int? =
	libVlcSourceTracks(type)
		.indexOfFirst { track -> track.index == streamIndex }
		.takeIf { index -> index >= 0 }

class LibVlcBackend(
	context: Context,
	private val instanceOptionsProvider: () -> LibVlcInstanceOptions = { LibVlcInstanceOptions() },
) : BasePlayerBackend(), TrackSelectionBackend, IVLCVout.OnNewVideoLayoutListener {
	private companion object {
		const val TICK_INTERVAL_MS = 250L
	}

	override val reportsBufferedPosition = false

	var videoDecoder = LibVlcVideoDecoder.DISABLED
		private set
	private var forcedVideoDecoder: LibVlcVideoDecoder? = null
	private val effectiveVideoDecoder: LibVlcVideoDecoder
		get() = forcedVideoDecoder ?: videoDecoder
	private var playbackOptions = LibVlcPlaybackOptions()
	override val videoDecoderOptions = LibVlcVideoDecoder.entries.map { decoder ->
		VideoDecoderOption(
			id = decoder.name,
			label = when (decoder) {
				LibVlcVideoDecoder.AUTOMATIC -> "Auto"
				LibVlcVideoDecoder.DISABLED -> "Software"
				LibVlcVideoDecoder.DECODING -> "Decoding"
				LibVlcVideoDecoder.FULL -> "Full"
			},
		)
	}
	override val selectedVideoDecoderOption: VideoDecoderOption
		get() = requireNotNull(effectiveVideoDecoder.toOption())
	override val forcedVideoDecoderOption: VideoDecoderOption?
		get() = forcedVideoDecoder?.toOption()

	private val appContext = context.applicationContext
	private var subtitleStyle: PlayerSubtitleStyle? = null
	private var playbackSpeed = 1f
	private var appliedInstanceOptions = currentInstanceOptions()
	private var libVlc = createLibVlc(appliedInstanceOptions)
	private var player = createPlayer(libVlc, appliedInstanceOptions)
	private val handler = Handler(Looper.getMainLooper())
	private val timedEvents = TimedEventTracker()
	private var liveTvBufferDuration: Duration? = null
	private var currentStream: PlayableMediaStream? = null
	private var surfaceView: PlayerSurfaceView? = null
	private var subtitleView: PlayerSubtitleView? = null
	private var subtitleSurface: SurfaceView? = null
	private var subtitleLayoutListener: View.OnLayoutChangeListener? = null
	private var lastTickPosition = Duration.ZERO
	private var endReported = false
	private var initialTracksApplied = false

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
		attachViews()
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
		attachViews()
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

	private fun attachViews() {
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

	override fun replaceItem(item: QueueEntry) = playItem(item)

	override fun setLiveTvBufferDuration(duration: Duration?) {
		liveTvBufferDuration = duration
	}

	private fun setMedia(stream: PlayableMediaStream) {
		ensureInstanceOptions()
		currentStream = stream
		endReported = false
		initialTracksApplied = false
		lastTickPosition = Duration.ZERO
		listener?.onSubtitleTimingOffsetSupportChange(true)
		val media = Media(libVlc, Uri.parse(stream.url))
		try {
			media.setVideoDecoder(effectiveVideoDecoder)
			libVlcMediaOptions(stream.queueEntry.isLiveTv, liveTvBufferDuration, playbackOptions).forEach(media::addOption)
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
		player.stop()
		currentStream = null
		endReported = false
		lastTickPosition = Duration.ZERO
		forcedVideoDecoder = null
		listener?.onSubtitleTimingOffsetSupportChange(false)
	}

	override fun seekTo(position: Duration) {
		val previous = getPositionInfo()
		if (player.setTime(position.inWholeMilliseconds) < 0) {
			Timber.w("LibVLC rejected seek to %d ms", position.inWholeMilliseconds)
			return
		}
		timedEvents.advance(previous.active, position, previous.duration, natural = false)
		lastTickPosition = position
	}

	override fun setScrubbing(scrubbing: Boolean) = Unit

	override fun setSpeed(speed: Float) {
		playbackSpeed = speed
		player.rate = speed
	}

	fun setVideoDecoder(decoder: LibVlcVideoDecoder) {
		videoDecoder = decoder
	}

	fun setPlaybackOptions(options: LibVlcPlaybackOptions) {
		playbackOptions = options
	}

	override fun setForcedVideoDecoderOption(option: VideoDecoderOption?) {
		forcedVideoDecoder = option?.let { LibVlcVideoDecoder.valueOf(it.id) }
	}

	private fun LibVlcVideoDecoder.toOption() = videoDecoderOptions.firstOrNull { option -> option.id == name }

	private fun currentInstanceOptions(): LibVlcInstanceOptions {
		val options = instanceOptionsProvider()
		return subtitleStyle?.let { style ->
			options.copy(arguments = options.arguments + style.libVlcOptions())
		} ?: options
	}

	private fun createLibVlc(options: LibVlcInstanceOptions) =
		LibVLC(appContext, ArrayList(options.arguments))

	private fun createPlayer(libVlc: LibVLC, options: LibVlcInstanceOptions) =
		MediaPlayer(libVlc).apply {
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
		libVlc.release()

		appliedInstanceOptions = desiredOptions
		libVlc = createLibVlc(desiredOptions)
		player = createPlayer(libVlc, desiredOptions)
		attachViews()
	}

	override fun setSubtitleTiming(offset: Duration, speed: Float) {
		if (!player.setSpuDelay(offset.inWholeMicroseconds)) Timber.w("LibVLC rejected subtitle timing offset")
		if (speed != 1f) Timber.w("LibVLC does not support subtitle timing speed")
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
		return PlaybackFrameStats(
			droppedFrames = stats?.lostPictures ?: 0,
			corruptedFrames = stats?.demuxCorrupted ?: 0,
			playerName = "LibVLC",
			videoDecodedFrames = stats?.decodedVideo ?: 0,
			videoDecoderName = "LibVLC ${effectiveVideoDecoder.name.lowercase()}",
			audioDecoderName = "LibVLC",
			subtitleExtractor = "LibVLC",
			subtitleRender = "LibVLC",
		)
	}

	private fun onPlayerEvent(event: MediaPlayer.Event) {
		when (event.type) {
			MediaPlayer.Event.Opening -> listener?.onPlayStateChange(PlayState.BUFFERING)
			MediaPlayer.Event.Buffering -> bufferingPlayState(event.buffering)?.let { listener?.onPlayStateChange(it) }
			MediaPlayer.Event.Playing -> {
				applyInitialTrackSelection()
				handler.removeCallbacks(tick)
				lastTickPosition = getPositionInfo().active
				handler.post(tick)
				listener?.onPlayStateChange(PlayState.PLAYING)
			}
			MediaPlayer.Event.Paused -> {
				handler.removeCallbacks(tick)
				listener?.onPlayStateChange(PlayState.PAUSED)
			}
			MediaPlayer.Event.Stopped -> {
				handler.removeCallbacks(tick)
				listener?.onPlayStateChange(PlayState.STOPPED)
			}
			MediaPlayer.Event.EndReached -> {
				handler.removeCallbacks(tick)
				listener?.onPlayStateChange(PlayState.STOPPED)
				if (!endReported) currentStream?.let { listener?.onMediaStreamEnd(it) }
				endReported = true
			}
			MediaPlayer.Event.EncounteredError -> {
				handler.removeCallbacks(tick)
				listener?.onPlaybackError(PlaybackError("LIBVLC_ERROR"))
				listener?.onPlayStateChange(PlayState.ERROR)
			}
		}
	}

	private fun applyInitialTrackSelection() {
		if (initialTracksApplied) return
		initialTracksApplied = true
		val stream = currentStream ?: return
		stream.selectedAudioStreamIndex?.let { streamIndex ->
			stream.sourceTrackIndex(TrackType.AUDIO, streamIndex)
				?.let { index -> player.audioTracks?.getOrNull(index) }
				?.let { player.setAudioTrack(it.id) }
		}
		stream.selectedSubtitleStreamIndex?.let { streamIndex ->
			if (streamIndex < 0) {
				player.setSpuTrack(-1)
			} else {
				stream.sourceTrackIndex(TrackType.SUBTITLE, streamIndex)
					?.let { index -> player.spuTracks?.filter { it.id >= 0 }?.getOrNull(index) }
					?.let { player.setSpuTrack(it.id) }
			}
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
		if (width > 0 && height > 0) listener?.onVideoSizeChange(width, height)
	}

	override fun getAvailableTracks(type: TrackType): List<PlayerTrack> {
		val sourceTracks = currentStream?.libVlcSourceTracks(type).orEmpty()
		val tracks = when (type) {
			TrackType.AUDIO -> player.audioTracks?.toList().orEmpty()
			TrackType.SUBTITLE -> player.spuTracks?.filter { it.id >= 0 }.orEmpty()
		}
		val selectedId = when (type) {
			TrackType.AUDIO -> player.audioTrack
			TrackType.SUBTITLE -> player.spuTrack
		}
		return tracks.mapIndexed { index, track ->
			PlayerTrack(
				index = index,
				type = type,
				label = track.name,
				language = null,
				codec = sourceTracks.getOrNull(index)?.codec,
				isSelected = track.id == selectedId,
				streamIndex = sourceTracks.getOrNull(index)?.index,
				trackIndex = track.id,
			)
		}
	}

	override fun selectTrack(type: TrackType, index: Int): Boolean {
		if (type == TrackType.SUBTITLE && index == -1) return player.setSpuTrack(-1)
		val track = when (type) {
			TrackType.AUDIO -> player.audioTracks?.getOrNull(index)
			TrackType.SUBTITLE -> player.spuTracks?.filter { it.id >= 0 }?.getOrNull(index)
		} ?: return false
		return when (type) {
			TrackType.AUDIO -> player.setAudioTrack(track.id)
			TrackType.SUBTITLE -> player.setSpuTrack(track.id)
		}
	}
}

internal fun bufferingPlayState(percent: Float): PlayState? =
	PlayState.PLAYING.takeIf { percent >= 100f }
