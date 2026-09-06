package org.jellyfin.playback.mpv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import org.jellyfin.playback.core.PlaybackBufferOptions
import org.jellyfin.playback.core.backend.BasePlayerBackend
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
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
import org.jellyfin.playback.core.mediastream.startPosition
import org.jellyfin.playback.core.mediastream.totalBitrate
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PlaybackFrameStats
import org.jellyfin.playback.core.model.formatBufferBytes
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.isLiveTv
import org.jellyfin.playback.core.support.PlaySupportReport
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.timedevent.TimedEventTracker
import org.jellyfin.playback.core.ui.PlayerSubtitleStyle
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView
import timber.log.Timber
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal fun shouldSelectExternalSubtitle(
	subtitle: ExternalSubtitle,
	selectedSubtitleStreamIndex: Int?,
) = when (selectedSubtitleStreamIndex) {
	null -> subtitle.isDefault
	else -> subtitle.index == selectedSubtitleStreamIndex
}

internal fun PlayableMediaStream.mpvSourceTracks(type: TrackType): List<MediaStreamTrack> = when (type) {
	TrackType.AUDIO -> tracks.filterIsInstance<MediaStreamAudioTrack>()
	TrackType.SUBTITLE -> {
		val subtitles = tracks.filterIsInstance<MediaStreamSubtitleTrack>()
		subtitles.filterNot { it.isExternal } + externalSubtitles.mapNotNull { external ->
			subtitles.firstOrNull { subtitle -> subtitle.isExternal && subtitle.index == external.index }
		}
	}
}

internal fun shouldReadLibMPVStatProperty(lastMissNanos: Long?, nowNanos: Long, retryNanos: Long) =
	lastMissNanos == null || nowNanos - lastMissNanos >= retryNanos

private data class LibMPVTrack(
	val id: Int,
	val type: TrackType,
	val title: String?,
	val language: String?,
	val codec: String?,
	val isSelected: Boolean,
	val ffIndex: Int?,
	val isExternal: Boolean,
	val externalFilename: String?,
)

class LibMPVBackend(
	context: Context,
	private val videoDecoderProvider: (() -> LibMPVVideoDecoder)? = null,
	private val playbackOptionsProvider: (() -> LibMPVPlaybackOptions)? = null,
	private val gpuApiVersionProvider: (String) -> String? = { null },
) : BasePlayerBackend(), TrackSelectionBackend, SurfaceHolder.Callback {
	private companion object {
		const val TICK_INTERVAL_MS = 250L
		val FRAME_STAT_PROPERTY_RETRY_NANOS = 10.seconds.inWholeNanoseconds
	}

	override val reportsBufferedPosition = true
	override val supportsSubtitleTimingSpeed = true

	var videoDecoder = videoDecoderProvider?.invoke() ?: LibMPVVideoDecoder.AUTOMATIC
		private set
	private var forcedVideoDecoder: LibMPVVideoDecoder? = null
	private val effectiveVideoDecoder: LibMPVVideoDecoder
		get() = effectiveLibMPVVideoDecoder(
			configured = videoDecoder,
			forced = forcedVideoDecoder,
			softwareForLiveTv = playbackOptions.softwareDecodingForLiveTv,
			isLiveTv = currentStream?.queueEntry?.isLiveTv == true,
			videoPreset = playbackOptions.videoPreset,
		)
	private var playbackOptions = playbackOptionsProvider?.invoke() ?: LibMPVPlaybackOptions.DEFAULT
	private val vulkanSupported = isLibMPVVulkanSupported(context)
	private val requestedGpuApi: String
		get() = if (playbackOptions.videoPreset == LibMPVVideoPreset.OPTIMIZED_8K) "opengl" else playbackOptions.gpuApi
	private val effectiveVideoDecoderValue: String
		get() = effectiveVideoDecoder.mpvValue
	private val effectiveVideoOutput: String
		get() = playbackOptions.videoOutput

	override val videoDecoderOptions = LibMPVVideoDecoder.entries.map { decoder ->
		VideoDecoderOption(id = decoder.name, label = decoder.label)
	}
	override val selectedVideoDecoderOption: VideoDecoderOption
		get() = requireNotNull(effectiveVideoDecoder.toOption())
	override val forcedVideoDecoderOption: VideoDecoderOption?
		get() = forcedVideoDecoder?.toOption()

	private val appContext = context.applicationContext
	private val presetDirectory = appContext.filesDir.resolve("mpv-presets")
	private val handler = Handler(Looper.getMainLooper())
	private val playerLock = Any()
	private var player: MPV
	private var playerObserver: MPV.EventObserver? = null
	private var released = false
	private val playerLogObserver = object : MPV.LogObserver {
		override fun logMessage(prefix: String, level: Int, text: String) {
			val message = text.trim()
			if (message.isEmpty()) return
			val source = prefix.takeIf(String::isNotBlank)?.let { "[$it] " }.orEmpty()
			when {
				level <= MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR -> Timber.e("MPV %s%s", source, message)
				level <= MPV.mpvLogLevel.MPV_LOG_LEVEL_WARN -> Timber.w("MPV %s%s", source, message)
				level <= MPV.mpvLogLevel.MPV_LOG_LEVEL_INFO -> Timber.i("MPV %s%s", source, message)
				else -> Timber.d("MPV %s%s", source, message)
			}
		}
	}
	@Volatile
	private var playerGeneration = 0L
	private var appliedInstanceOptions = emptyMap<String, String>()
	private var appliedGpuApi = requestedGpuApi
	private val timedEvents = TimedEventTracker()

	private var bufferOptions = PlaybackBufferOptions()
	private var currentStream: PlayableMediaStream? = null
	private var surfaceView: PlayerSurfaceView? = null
	private var surfaceHolder: SurfaceHolder? = null
	private var surfaceAttached = false
	private var subtitleView: PlayerSubtitleView? = null
	private var subtitleStyle: PlayerSubtitleStyle? = null
	private var subtitleTimingOffset = Duration.ZERO
	private var subtitleTimingSpeed = 1f
	private var playbackSpeed = 1f
	private var lastTickPosition = Duration.ZERO
	private var lastPositionInfo = PositionInfo.EMPTY
	private var endReported = false
	private var externalSubtitlesAdded = false
	private var loadRequested = false
	private var activePlaylistEntryId: Long? = null
	private var fileLoaded = false
	private var playbackRestarted = false
	private var isPaused = true
	private var pausedForCache = false
	private var seeking = false
	private var rebufferWaitSeconds: Double? = null
	private var terminalState: PlayState? = PlayState.STOPPED
	private var lastReportedState: PlayState? = null
	private var videoWidth = 0
	private var videoHeight = 0
	private var tracks = emptyList<LibMPVTrack>()
	private var notifiedTracks = emptyList<LibMPVTrack>()
	private val pendingInitialTrackTypes = mutableSetOf<TrackType>()
	private val appliedPresetOptions = mutableSetOf<String>()
	private val appliedCustomOptions = mutableSetOf<String>()
	private val frameStatPropertyMisses = mutableMapOf<String, Long>()

	private val tick = object : Runnable {
		override fun run() {
			if (resolvePlayState() != PlayState.PLAYING) return
			val position = getPositionInfo()
			timedEvents.advance(lastTickPosition, position.active, position.duration, natural = true)
			lastTickPosition = position.active
			handler.postDelayed(this, TICK_INTERVAL_MS)
		}
	}

	init {
		synchronized(playerLock) {
			val options = currentInstanceOptions()
			player = createPlayer(options)
			appliedInstanceOptions = LinkedHashMap(options)
			finishPlayerSetup(player)
		}
	}

	private fun currentCustomOptions(): Map<String, String> = playbackOptions.customOptions
		.filterKeys { option -> !isLibMPVOptionManagedByJellyfin(option) }

	private fun currentInstanceOptions(): LinkedHashMap<String, String> = linkedMapOf(
		"config" to "no",
		"gpu-shader-cache-dir" to appContext.cacheDir.absolutePath,
		"icc-cache-dir" to appContext.cacheDir.absolutePath,
		"sub-font-provider" to "fontconfig",
		"embeddedfonts" to "yes",
		// Jellyfin reuses one backend for the full queue, so keep libMPV alive after EOF.
		"idle" to "yes",
		"osc" to "no",
		"osd-level" to "0",
		"sub-auto" to "no",
		"audio-file-auto" to "no",
		"gapless-audio" to "no",
		"cover-art-auto" to "no",
		"autoload-files" to "no",
		"ytdl" to "no",
		"keep-open" to "no",
		"force-window" to "no",
		"input-default-bindings" to "no",
		"input-builtin-bindings" to "no",
		"input-vo-keyboard" to "no",
		"terminal" to "no",
	).apply {
		putAll(playbackOptions.managedOptions(vulkanSupported))
		put("hwdec", effectiveVideoDecoderValue)
		putAll(currentPresetOptions())
		putAll(currentCustomOptions())
	}

	private fun createPlayer(options: Map<String, String>): MPV {
		presetDirectory.mkdirs()
		BUNDLED_PRESET_SHADERS.forEach { name ->
			appContext.assets.open("mpv-anime/shaders/$name").use { input ->
				presetDirectory.resolve(name).outputStream().use(input::copyTo)
			}
		}
		return MPV(appContext, options = options)
	}

	private fun setStartupOption(target: MPV, name: String, value: String) {
		runCatching { target.setOptionString(name, value) }
			.onSuccess { result ->
				if (result < 0) Timber.w("MPV rejected startup option %s=%s with code %d", name, value, result)
			}
			.onFailure { error -> Timber.w(error, "Unable to set MPV startup option %s=%s", name, value) }
	}

	private fun finishPlayerSetup(target: MPV) {
		val generation = ++playerGeneration
		val observer = PlayerObserver(generation)
		playerObserver = observer
		target.addObserver(observer)
		target.addLogObserver(playerLogObserver)
		observeProperties(target)

		// Match BaseMPVView: keep the VO dormant until a valid Android surface exists.
		setStartupOption(target, "force-window", "no")
		appliedPresetOptions.clear()
		appliedPresetOptions += currentPresetOptions().keys
		appliedCustomOptions.clear()
		appliedCustomOptions += currentCustomOptions().keys
		applySubtitleStyle(subtitleStyle)
		applySubtitleTiming()
		applyPlaybackSpeed()

		surfaceHolder?.takeIf { holder -> holder.surface.isValid }?.let(::attachSurface)
	}

	private fun ensureInstanceOptions(forceRecreate: Boolean = false) = synchronized(playerLock) {
		val desiredOptions = currentInstanceOptions()
		if (forceRecreate || desiredOptions != appliedInstanceOptions) {
			recreatePlayer(desiredOptions)
		} else {
			appliedGpuApi = requestedGpuApi
		}
	}

	private fun recreatePlayer(desiredOptions: Map<String, String>) {
		// Create first so a bad expert option does not destroy a working player.
		val replacement = createPlayer(desiredOptions)
		val previous = player
		val previousObserver = playerObserver

		handler.removeCallbacks(tick)
		detachSurface(previous)
		// Invalidate native callbacks already queued before observer removal.
		playerGeneration++
		previousObserver?.let(previous::removeObserver)
		previous.removeLogObserver(playerLogObserver)
		runCatching { previous.close() }
			.onFailure { error -> Timber.w(error, "Unable to destroy previous MPV instance cleanly") }

		player = replacement
		playerObserver = null
		appliedInstanceOptions = LinkedHashMap(desiredOptions)
		appliedGpuApi = requestedGpuApi
		surfaceAttached = false
		finishPlayerSetup(replacement)
	}

	private inner class PlayerObserver(
		private val generation: Long,
	) : MPV.EventObserver {
		override fun eventProperty(property: String) = Unit

		override fun eventProperty(property: String, value: Long) =
			handleEventProperty(generation, property, value)

		override fun eventProperty(property: String, value: Boolean) =
			handleEventProperty(generation, property, value)

		override fun eventProperty(property: String, value: String) = Unit

		override fun eventProperty(property: String, value: Double) = Unit

		override fun eventProperty(property: String, value: MPVNode) =
			handleEventProperty(generation, property, value)

		override fun event(eventId: Int, data: MPVNode) = handleEvent(generation, eventId, data)
	}

	private fun observeProperties(target: MPV) {
		target.observeProperty("pause", MPV.mpvFormat.MPV_FORMAT_FLAG)
		target.observeProperty("paused-for-cache", MPV.mpvFormat.MPV_FORMAT_FLAG)
		target.observeProperty("seeking", MPV.mpvFormat.MPV_FORMAT_FLAG)
		target.observeProperty("track-list", MPV.mpvFormat.MPV_FORMAT_NODE)
		target.observeProperty("video-params/w", MPV.mpvFormat.MPV_FORMAT_INT64)
		target.observeProperty("video-params/h", MPV.mpvFormat.MPV_FORMAT_INT64)
	}

	override fun supportsStream(stream: MediaStream): PlaySupportReport = object : PlaySupportReport {
		override val canPlay = true
	}

	override fun setListener(eventListener: PlayerBackendEventListener?) {
		super.setListener(eventListener)
		publishPlayState(force = true)
	}

	override fun setSurfaceView(surfaceView: PlayerSurfaceView?) {
		if (this.surfaceView === surfaceView) return

		surfaceHolder?.removeCallback(this)
		detachSurface()
		this.surfaceView = surfaceView
		surfaceHolder = surfaceView?.surface?.holder?.also { holder ->
			holder.addCallback(this)
			if (holder.surface.isValid) attachSurface(holder)
		}
	}

	override fun surfaceCreated(holder: SurfaceHolder) {
		if (holder === surfaceHolder) attachSurface(holder)
	}

	override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
		if (holder !== surfaceHolder) return
		if (!surfaceAttached) attachSurface(holder)
		updateSurfaceSize(width, height)
	}

	override fun surfaceDestroyed(holder: SurfaceHolder) {
		if (holder === surfaceHolder) detachSurface()
	}

	private fun attachSurface(holder: SurfaceHolder, target: MPV = player) {
		if (!holder.surface.isValid || !target.isInitialized) return
		if (surfaceAttached) detachSurface(target)
		runCatching {
			target.attachSurface(holder.surface)
			target.setPropertyString("force-window", "yes")
			target.setPropertyString("vo", effectiveVideoOutput)
			surfaceAttached = true
			val frame = holder.surfaceFrame
			updateSurfaceSize(frame.width(), frame.height(), target)
		}.onFailure { error ->
			Timber.e(error, "Unable to attach MPV video surface")
			surfaceAttached = false
		}
	}

	private fun detachSurface(target: MPV = player) {
		if (!surfaceAttached || !target.isInitialized) return
		runCatching {
			target.setPropertyString("vo", "null")
			target.setPropertyString("force-window", "no")
			target.detachSurface()
		}.onFailure { error ->
			Timber.w(error, "Unable to detach MPV video surface cleanly")
		}
		surfaceAttached = false
	}

	private fun updateSurfaceSize(width: Int, height: Int, target: MPV = player) {
		if (width <= 0 || height <= 0) return
		runCatching { target.setPropertyString("android-surface-size", "${width}x${height}") }
			.onFailure { error -> Timber.w(error, "Unable to update MPV surface size") }
	}

	override fun setSubtitleView(surfaceView: PlayerSubtitleView?) {
		subtitleView?.onSubtitleStyleChanged = null
		subtitleView = surfaceView
		subtitleStyle = surfaceView?.subtitleStyle
		surfaceView?.onSubtitleStyleChanged = ::applySubtitleStyle
		applySubtitleStyle(subtitleStyle)
	}

	private fun applySubtitleStyle(style: PlayerSubtitleStyle?) {
		if (style == null) return
		subtitleStyle = style
		setProperty("sub-ass-override", playbackOptions.subtitleAssOverride)
		setProperty("sub-font-size", mpvSubtitleFontSize(style.textSizeDp).toString())
		setProperty("sub-bold", if (style.textWeight >= 600) "yes" else "no")
		setProperty("sub-color", style.textColor.mpvColor())
		setProperty("sub-back-color", style.backgroundColor.mpvColor())
		setProperty("sub-outline-color", style.edgeColor.mpvColor())
		setProperty("sub-outline-size", "2.5")
		setProperty("sub-shadow-offset", "0")
		setProperty(
			"sub-border-style",
			if (style.backgroundColor.alpha() > 0) "background-box" else "outline-and-shadow",
		)
		setProperty("sub-margin-y", mpvSubtitleMarginY(style.bottomPaddingFraction).toString())
	}

	override fun prepareItem(item: QueueEntry) = Unit

	override fun playItem(item: QueueEntry) {
		val stream = requireNotNull(item.mediaStream)
		if (stream == currentStream && terminalState == null) return play()
		setMedia(stream)
	}

	override fun replaceItem(item: QueueEntry) {
		setMedia(requireNotNull(item.mediaStream))
	}

	private fun setMedia(stream: PlayableMediaStream) {
		ensureInstanceOptions(currentStream?.queueEntry !== stream.queueEntry)
		currentStream = stream
		endReported = false
		externalSubtitlesAdded = false
		loadRequested = true
		activePlaylistEntryId = null
		fileLoaded = false
		playbackRestarted = false
		isPaused = false
		pausedForCache = false
		seeking = false
		terminalState = null
		tracks = emptyList()
		notifiedTracks = emptyList()
		videoWidth = 0
		videoHeight = 0
		pendingInitialTrackTypes.clear()
		if (stream.conversionMethod == MediaConversionMethod.None) {
			if (stream.selectedAudioStreamIndex != null) pendingInitialTrackTypes += TrackType.AUDIO
			if (stream.selectedSubtitleStreamIndex != null) pendingInitialTrackTypes += TrackType.SUBTITLE
		}
		val startPosition = stream.queueEntry.startPosition ?: Duration.ZERO
		lastPositionInfo = PositionInfo(startPosition, startPosition, Duration.ZERO)
		lastTickPosition = startPosition
		listener?.onSubtitleTimingOffsetSupportChange(true)
		publishPlayState(force = true)

		applyPlaybackOptions()
		applyVideoDecoder()
		applyBufferOptions(stream)
		applySubtitleStyle(subtitleStyle)
		applySubtitleTiming()
		applyPlaybackSpeed()

		val startOption = stream.queueEntry.startPosition.mpvStartOption()
		val loaded = if (startOption == null) {
			runCommand("loadfile", stream.url, "replace")
		} else {
			runCommand("loadfile", stream.url, "replace", "-1", startOption)
		}
		if (!loaded) {
			handlePlaybackError("Unable to issue MPV loadfile command")
			return
		}
		setBooleanProperty("pause", false)
	}

	override fun setBufferOptions(options: PlaybackBufferOptions) {
		bufferOptions = options
		currentStream?.let(::applyBufferOptions)
	}

	private fun applyBufferOptions(stream: PlayableMediaStream) {
		val bitrate = stream.totalBitrate()
		val configuration = bufferOptions
			.toLibMPVBufferConfiguration(stream.queueEntry.isLiveTv)
			.cappedToBytes(bitrate, bufferOptions.maxBufferBytes)
		rebufferWaitSeconds = configuration.rebufferWaitSeconds

		setOption("cache", if (configuration.cacheSeconds == null) "auto" else "yes")
		setOption("cache-pause", "yes")
		setOption("cache-pause-initial", if (configuration.initialWaitSeconds == null) "no" else "yes")
		setCachePauseWait(configuration.initialWaitSeconds ?: configuration.rebufferWaitSeconds)

		if (configuration.cacheSeconds == null) {
			restoreOptionDefault("cache-secs")
			restoreOptionDefault("demuxer-readahead-secs")
			restoreOptionDefault("demuxer-max-bytes")
			restoreOptionDefault("demuxer-max-back-bytes")
		} else {
			val cacheSeconds = configuration.cacheSeconds.toLibMPVString()
			setOption("cache-secs", cacheSeconds)
			val maximumBytes = bufferOptions.maxBufferBytes
			val forwardBytes = mpvCacheBytes(configuration.cacheSeconds, bitrate, maximumBytes)
			if (forwardBytes != null && maximumBytes != null) {
				setOption("demuxer-max-bytes", forwardBytes.toString())
				setOption("demuxer-max-back-bytes", (maximumBytes - forwardBytes).coerceAtLeast(0).toString())
			} else {
				restoreOptionDefault("demuxer-max-bytes")
				restoreOptionDefault("demuxer-max-back-bytes")
			}
		}
	}

	private fun setCachePauseWait(seconds: Double?) {
		if (seconds == null) restoreOptionDefault("cache-pause-wait")
		else setOption("cache-pause-wait", seconds.toLibMPVString())
	}

	override fun onActivated() {
		setConfiguration(
			decoder = videoDecoderProvider?.invoke() ?: videoDecoder,
			options = playbackOptionsProvider?.invoke() ?: playbackOptions,
		)
	}

	override fun play() {
		terminalState = null
		setBooleanProperty("pause", false)
	}

	override fun pause() {
		setBooleanProperty("pause", true)
	}

	override fun stop() {
		handler.removeCallbacks(tick)
		loadRequested = false
		endReported = true
		lastPositionInfo = getPositionInfo()
		runCommand("stop")
		currentStream = null
		activePlaylistEntryId = null
		fileLoaded = false
		playbackRestarted = false
		isPaused = true
		pausedForCache = false
		seeking = false
		terminalState = PlayState.STOPPED
		tracks = emptyList()
		notifiedTracks = emptyList()
		pendingInitialTrackTypes.clear()
		lastTickPosition = Duration.ZERO
		forcedVideoDecoder = null
		ensureInstanceOptions()
		listener?.onSubtitleTimingOffsetSupportChange(false)
		publishPlayState(force = true)
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
		synchronized(playerLock) {
			if (released) return
			cleanup()
			playerObserver?.let(player::removeObserver)
			playerObserver = null
			player.removeLogObserver(playerLogObserver)
			runCatching { if (player.isInitialized) player.close() }
				.onFailure { error -> Timber.w(error, "Unable to destroy MPV instance cleanly") }
			currentStream = null
			terminalState = PlayState.STOPPED
			released = true
		}
	}

	override fun seekTo(position: Duration) {
		val previous = getPositionInfo()
		val targetSeconds = position.inWholeMilliseconds.coerceAtLeast(0) / 1_000.0
		if (!runCommand("seek", targetSeconds.toLibMPVString(), "absolute+exact")) return
		playbackRestarted = false
		seeking = true
		publishPlayState()
		timedEvents.advance(previous.active, position, previous.duration, natural = false)
		lastTickPosition = position
	}

	override fun setScrubbing(scrubbing: Boolean) = Unit

	override fun setSpeed(speed: Float) {
		playbackSpeed = speed.coerceAtLeast(0.01f)
		applyPlaybackSpeed()
	}

	private fun applyPlaybackSpeed() {
		setDoubleProperty("speed", playbackSpeed.toDouble())
	}

	fun setConfiguration(decoder: LibMPVVideoDecoder, options: LibMPVPlaybackOptions) {
		if (videoDecoder == decoder && playbackOptions == options) return
		videoDecoder = decoder
		playbackOptions = options
		applyConfigurationChange()
	}

	fun setVideoDecoder(decoder: LibMPVVideoDecoder) {
		setConfiguration(decoder, playbackOptions)
	}

	private fun applyVideoDecoder() {
		setOption("hwdec", effectiveVideoDecoderValue)
	}

	fun setPlaybackOptions(options: LibMPVPlaybackOptions) {
		setConfiguration(videoDecoder, options)
	}

	private fun applyConfigurationChange() {
		val canRecreateImmediately = currentStream == null ||
			terminalState == PlayState.STOPPED ||
			terminalState == PlayState.ERROR
		if (canRecreateImmediately) {
			ensureInstanceOptions()
			return
		}

		// Apply runtime-capable values immediately. Recreate before the next item so
		// startup-only and reload-required options use the selected values as well.
		applyPlaybackOptions()
		applyVideoDecoder()
		applySubtitleStyle(subtitleStyle)
	}

	private fun applyPlaybackOptions() {
		val presetOptions = currentPresetOptions()
		val customOptions = currentCustomOptions()
		val removedPresetOptions = appliedPresetOptions - presetOptions.keys
		val removedOptions = appliedCustomOptions - customOptions.keys

		removedPresetOptions.forEach(::restoreOptionDefault)
		removedOptions.forEach(::restoreCustomOption)
		playbackOptions.managedOptions(vulkanSupported).forEach(::setOption)
		presetOptions.forEach(::setOption)
		customOptions.forEach(::setOption)

		appliedPresetOptions.clear()
		appliedPresetOptions += presetOptions.keys
		appliedCustomOptions.clear()
		appliedCustomOptions += customOptions.keys
	}

	private fun currentPresetOptions() = playbackOptions.presetOptions(presetDirectory)

	private fun restoreCustomOption(name: String) = restoreOptionDefault(name)

	private fun restoreOptionDefault(name: String) {
		val defaultValue = getOptionInfoNode(name, "default-value").displayValue()
		if (defaultValue == null) {
			Timber.w("Unable to restore MPV option %s because libMPV did not report a default value", name)
		} else {
			setOption(name, defaultValue)
		}
	}

	/**
	 * Build an option catalog directly from the bundled libMPV. This makes the expert
	 * settings screen follow the exact native build instead of a hard-coded option list.
	 */
	fun getOptionCatalog(): List<LibMPVOptionInfo> = synchronized(playerLock) {
		runCatching {
			// Root key-action properties normally expose every option as a map. Accept
			// array-shaped native nodes as well, and conservatively augment the names
			// with property-list. Every candidate is still verified through option-info,
			// so ordinary properties cannot leak into the option catalog.
			val candidateNames = buildSet {
				sequenceOf("options", "option-info").forEach { property ->
					addAll(runCatching { player.getPropertyNode(property).optionNames() }
						.getOrDefault(emptySet()))
				}
				addAll(
					runCatching {
						player.getPropertyNode("property-list")
							?.asArray()
							.orEmpty()
							.mapNotNull(MPVNode::asString)
					}.getOrDefault(emptyList())
				)
			}

			val catalog = candidateNames
				.mapNotNull(::readOptionInfo)
				.distinctBy(LibMPVOptionInfo::name)
				.sortedBy(LibMPVOptionInfo::name)

			check(catalog.isNotEmpty()) { "libMPV did not report an option catalog" }
			catalog
		}.onFailure { error ->
			Timber.e(error, "Unable to read libMPV option catalog")
		}.getOrThrow()
	}

	private fun readOptionInfo(candidateName: String): LibMPVOptionInfo? {
		val info = runCatching {
			player.getPropertyNode("option-info/$candidateName")?.asMap()
		}.getOrNull() ?: return null
		val optionName = info["name"]?.asString()?.takeIf(String::isNotBlank) ?: return null

		return LibMPVOptionInfo(
			name = optionName,
			type = info["type"]?.asString(),
			currentValue = getOptionValue(optionName),
			defaultValue = info["default-value"].displayValue(),
			minimum = info["min"].displayValue(),
			maximum = info["max"].displayValue(),
			choices = info["choices"]
				?.asArray()
				.orEmpty()
				.mapNotNull { node -> node.displayValue() },
			expectsFile = info["expects-file"]?.asBoolean() == true,
			readOnly = isLibMPVOptionManagedByJellyfin(optionName),
		)
	}

	private fun getOptionValue(name: String): String? = runCatching {
		player.getPropertyNode("options/$name").displayValue()
	}.getOrNull() ?: runCatching {
		player.getPropertyString("options/$name")
	}.getOrNull()

	private fun getOptionInfoNode(name: String, property: String): MPVNode? = runCatching {
		player.getPropertyNode("option-info/$name/$property")
	}.getOrNull()

	override fun setForcedVideoDecoderOption(option: VideoDecoderOption?) {
		val decoder = option?.let { candidate ->
			LibMPVVideoDecoder.entries.firstOrNull { entry -> entry.name == candidate.id }
				?: throw IllegalArgumentException("Unknown MPV decoder option: ${candidate.id}")
		}
		if (forcedVideoDecoder == decoder) return
		forcedVideoDecoder = decoder
		val canRecreateImmediately = currentStream == null ||
			terminalState == PlayState.STOPPED ||
			terminalState == PlayState.ERROR
		if (canRecreateImmediately) ensureInstanceOptions() else applyVideoDecoder()
	}

	override fun reloadVideoDecoder(): Boolean =
		currentStream != null && terminalState == null && runCommand("video-reload")

	private fun LibMPVVideoDecoder.toOption() = videoDecoderOptions.firstOrNull { option -> option.id == name }

	override fun setSubtitleTiming(offset: Duration, speed: Float) {
		subtitleTimingOffset = offset
		subtitleTimingSpeed = speed.coerceIn(0.1f, 10f)
		applySubtitleTiming()
	}

	private fun applySubtitleTiming() {
		setDoubleProperty("sub-delay", subtitleTimingOffset.inWholeMilliseconds / 1_000.0)
		setDoubleProperty("sub-speed", subtitleTimingSpeed.toDouble())
	}

	override fun setTimedEvents(timedEvents: List<TimedEvent>) {
		this.timedEvents.setEvents(timedEvents)
	}

	override fun getPositionInfo(): PositionInfo {
		return mpvPositionInfo(
			activeSeconds = player.getPropertyDouble("time-pos"),
			durationSeconds = player.getPropertyDouble("duration"),
			cacheEndSeconds = player.getPropertyDouble("demuxer-cache-time"),
			cacheDurationSeconds = player.getPropertyDouble("demuxer-cache-duration"),
			fallback = lastPositionInfo,
		).also { lastPositionInfo = it }
	}

	override fun getFrameStats(): PlaybackFrameStats {
		fun <T> property(name: String, getter: () -> T?): T? {
			val now = System.nanoTime()
			if (!shouldReadLibMPVStatProperty(frameStatPropertyMisses[name], now, FRAME_STAT_PROPERTY_RETRY_NANOS)) return null
			return getter().also { value ->
				if (value == null) frameStatPropertyMisses[name] = now
				else frameStatPropertyMisses.remove(name)
			}
		}
		fun string(name: String) = property(name) { player.getPropertyString(name) }?.takeUnless(String::isBlank)
		fun integer(name: String) = property(name) { player.getPropertyInt(name) }
		fun double(name: String) = property(name) { player.getPropertyDouble(name) }?.takeIf(Double::isFinite)
		fun number(name: String, suffix: String = "", multiplier: Double = 1.0) =
			double(name)?.let { value -> String.format(Locale.US, "%.3f%s", value * multiplier, suffix) }
		fun correction(name: String) =
			double(name)?.let { value -> String.format(Locale.US, "%+.5f%%", (value - 1.0) * 100.0) }
		fun count(name: String) = integer(name)?.toString()

		val decoderDropped = integer("decoder-frame-drop-count") ?: 0
		val outputDropped = integer("frame-drop-count") ?: 0
		val hardwareDecoder = string("hwdec-current")?.takeUnless { it == "no" }
		val videoGamma = string("video-params/gamma")
		val dolbyVisionProfile = integer("current-tracks/video/dolby-vision-profile")
		val videoOutput = string("current-vo")
		val videoOutputDisplay = mpvSelectionDisplay(effectiveVideoOutput, videoOutput)
		val gpuApi = mpvGpuApi(string("current-gpu-context"))
		val gpuApiVersion = gpuApi?.let(gpuApiVersionProvider)
		val gpuApiDisplay = mpvGpuApiDisplay(
			requested = appliedGpuApi,
			actual = gpuApi,
			requestedVersion = when (appliedGpuApi) {
				"auto" -> null
				gpuApi -> gpuApiVersion
				else -> gpuApiVersionProvider(appliedGpuApi)
			},
			actualVersion = gpuApiVersion,
		)
		val audioFormat = string("audio-params/format")
		val hasHdr10Plus = double("video-params/scene-max-r") != null ||
			double("video-params/scene-max-g") != null ||
			double("video-params/scene-max-b") != null
		val bufferedBytes = property("demuxer-cache-state") {
			runCatching {
				player.getPropertyNode("demuxer-cache-state")
				?.asMap()
				?.get("fw-bytes")
				?.asInt()
			}.getOrNull()
		}
		val bufferDetails = formatLibMPVBufferDetails(
			bufferedBytes = bufferedBytes,
			isPausedForCache = string("paused-for-cache").equals("yes", ignoreCase = true),
			isCacheIdle = string("demuxer-cache-idle").equals("yes", ignoreCase = true),
			cacheSpeed = double("cache-speed"),
		)
		val backendDetails = buildMap {
			number("display-fps", " Hz")?.let { put("Display refresh", it) }
			number("estimated-display-fps", " Hz")?.let { put("Measured refresh", it) }
			string("display-sync-active")?.let { put("Display sync active", it) }
			correction("video-speed-correction")?.let { put("Video speed correction", it) }
			correction("audio-speed-correction")?.let { put("Audio speed correction", it) }
			number("avsync", " ms", 1_000.0)?.let { put("A/V sync", it) }
			number("total-avsync-change", " ms", 1_000.0)?.let { put("A/V sync correction", it) }
			count("mistimed-frame-count")?.let { put("Mistimed frames", it) }
			count("vo-delayed-frame-count")?.let { put("Delayed frames", it) }
			string("video-frame-info/picture-type")?.let { put("Picture type", it) }
			string("video-frame-info/tff")?.let { put("Top field first", it) }
			string("video-frame-info/repeat")?.let { put("Repeated frame", it) }
			string("deinterlace-active")?.let { put("Deinterlacing active", it) }
			number("vsync-ratio")?.let { put("VSync ratio", it) }
			number("vsync-jitter")?.let { put("VSync jitter", it) }
			videoOutputDisplay?.let { put("Video output", "$it${gpuApiDisplay?.let { api -> " ($api)" }.orEmpty()}") }
			string("current-ao")?.let { put("Audio output", it) }
			string("video-params/pixelformat")?.let { put("Output pixel format", it) }
			string("video-params/colormatrix")?.let { put("Color matrix", it) }
			string("video-params/primaries")?.let { put("Color primaries", it) }
			number("video-params/max-luma", " nits")?.let { put("Mastering peak", it) }
			number("video-params/max-cll", " nits")?.let { put("MaxCLL", it) }
			number("video-params/max-fall", " nits")?.let { put("MaxFALL", it) }
			string("audio-params/format")?.let { put("Audio format", it) }
		}
		return PlaybackFrameStats(
			droppedFrames = (decoderDropped.toLong() + outputDropped.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
			corruptedFrames = 0,
			playerName = "libMPV",
			videoDecoderFps = double("estimated-vf-fps")?.toFloat(),
			videoDecoderName = string("current-tracks/video/decoder-desc")
				?: string("current-tracks/video/decoder"),
			videoDecoderType = hardwareDecoder?.let { "Hardware ($it)" } ?: "Software",
			videoCodec = string("current-tracks/video/codec"),
			videoHdrMode = mpvHdrPipeline(videoGamma, dolbyVisionProfile, hasHdr10Plus),
			videoSourceFps = double("container-fps")?.toFloat(),
			videoBitrate = double("video-bitrate")?.toInt(),
			videoRange = string("video-params/colorlevels"),
			audioDecoderName = string("current-tracks/audio/decoder-desc")
				?: string("current-tracks/audio/decoder"),
			audioDecoderType = "Software",
			audioCodec = string("current-tracks/audio/codec"),
			audioBitrate = double("audio-bitrate")?.toInt(),
			audioChannels = string("audio-params/channels"),
			audioSampleRate = integer("audio-params/samplerate"),
			audioPassthroughSupported = audioFormat?.startsWith("spdif"),
			bufferedBytes = bufferDetails,
			subtitleExtractor = "libMPV",
			subtitleRender = "libass",
			subtitleParser = string("current-tracks/sub/codec"),
			subtitlePath = string("current-tracks/sub/external-filename"),
			backendDetails = backendDetails,
		)
	}

	private fun handleEventProperty(generation: Long, property: String, value: Long) = onPlayerEvent(generation) {
		when (property) {
			"video-params/w" -> {
				videoWidth = value.toInt().coerceAtLeast(0)
				publishVideoSize()
			}
			"video-params/h" -> {
				videoHeight = value.toInt().coerceAtLeast(0)
				publishVideoSize()
			}
		}
	}

	private fun handleEventProperty(generation: Long, property: String, value: Boolean) = onPlayerEvent(generation) {
		when (property) {
			"pause" -> isPaused = value
			"paused-for-cache" -> pausedForCache = value
			"seeking" -> seeking = value
			else -> return@onPlayerEvent
		}
		publishPlayState()
	}

	private fun handleEventProperty(generation: Long, property: String, value: MPVNode) = onPlayerEvent(generation) {
		if (property == "track-list") {
			updateTracks(value)
			applyInitialTrackSelection()
		}
	}

	private fun handleEvent(generation: Long, eventId: Int, data: MPVNode) = onPlayerEvent(generation) {
		when (eventId) {
			MPV.mpvEvent.MPV_EVENT_START_FILE -> {
				frameStatPropertyMisses.clear()
				loadRequested = false
				activePlaylistEntryId = data["playlist_entry_id"]?.asInt()
				fileLoaded = false
				playbackRestarted = false
				terminalState = null
				pausedForCache = false
				seeking = false
				publishPlayState(force = true)
			}
			MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> {
				fileLoaded = true
				terminalState = null
				addExternalSubtitles()
				refreshTracks()
				applyInitialTrackSelection()
				applySubtitleStyle(subtitleStyle)
				applySubtitleTiming()
				applyPlaybackSpeed()
				refreshVideoSize()
				publishPlayState(force = true)
			}
			MPV.mpvEvent.MPV_EVENT_VIDEO_RECONFIG -> refreshVideoSize()
			MPV.mpvEvent.MPV_EVENT_SEEK -> {
				playbackRestarted = false
				seeking = true
				publishPlayState()
			}
			MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
				frameStatPropertyMisses.clear()
				playbackRestarted = true
				seeking = false
				rebufferWaitSeconds?.let { setOption("cache-pause-wait", it.toLibMPVString()) }
				lastTickPosition = getPositionInfo().active
				publishPlayState(force = true)
			}
			MPV.mpvEvent.MPV_EVENT_END_FILE -> handleEndFile(data)
			MPV.mpvEvent.MPV_EVENT_SHUTDOWN -> {
				if (currentStream == null) {
					terminalState = PlayState.STOPPED
					publishPlayState(force = true)
				} else {
					handlePlaybackError("MPV instance shut down")
				}
			}
			MPV.mpvEvent.MPV_EVENT_QUEUE_OVERFLOW -> Timber.w("MPV event queue overflow")
		}
	}

	private fun handleEndFile(data: MPVNode) {
		val eventEntryId = data["playlist_entry_id"]?.asInt()
		if (eventEntryId != null && activePlaylistEntryId != null && eventEntryId != activePlaylistEntryId) return

		val reason = data["reason"]?.asString() ?: "unknown"
		// loadfile replace first unloads the old entry. Ignore that old end-file event;
		// a failed replacement emits start-file before its own end-file(error).
		if (loadRequested) return
		if (reason == "stop" && currentStream != null && terminalState == null) return

		fileLoaded = false
		playbackRestarted = false
		pausedForCache = false
		seeking = false
		handler.removeCallbacks(tick)
		when (reason) {
			"eof" -> {
				terminalState = PlayState.STOPPED
				publishPlayState(force = true)
				if (!endReported) currentStream?.let { stream -> listener?.onMediaStreamEnd(stream) }
				endReported = true
			}
			"stop", "quit" -> {
				terminalState = PlayState.STOPPED
				publishPlayState(force = true)
			}
			"redirect" -> {
				terminalState = null
				publishPlayState(force = true)
			}
			"error" -> {
				val fileError = data["file_error"]?.asString() ?: "unknown MPV file error"
				handlePlaybackError(fileError)
			}
			else -> handlePlaybackError("MPV ended playback for reason: $reason")
		}
	}

	private fun handlePlaybackError(message: String) {
		Timber.e("MPV playback error: %s", message)
		handler.removeCallbacks(tick)
		playbackRestarted = false
		terminalState = PlayState.ERROR
		listener?.onPlaybackError(PlaybackError("MPV_ERROR"))
		publishPlayState(force = true)
	}

	private fun refreshVideoSize() {
		videoWidth = player.getPropertyInt("video-params/w")?.coerceAtLeast(0) ?: videoWidth
		videoHeight = player.getPropertyInt("video-params/h")?.coerceAtLeast(0) ?: videoHeight
		publishVideoSize()
	}

	private fun publishVideoSize() {
		if (videoWidth > 0 && videoHeight > 0) listener?.onVideoSizeChange(videoWidth, videoHeight)
	}

	private fun resolvePlayState(): PlayState {
		terminalState?.let { return it }
		if (currentStream == null) return PlayState.STOPPED
		if (!fileLoaded || !playbackRestarted || pausedForCache || seeking) return PlayState.BUFFERING
		return if (isPaused) PlayState.PAUSED else PlayState.PLAYING
	}

	private fun publishPlayState(force: Boolean = false) {
		val state = resolvePlayState()
		if (!force && state == lastReportedState) return
		lastReportedState = state
		handler.removeCallbacks(tick)
		if (state == PlayState.PLAYING) {
			lastTickPosition = getPositionInfo().active
			handler.post(tick)
		}
		listener?.onPlayStateChange(state)
	}

	private fun addExternalSubtitles() {
		if (externalSubtitlesAdded) return
		externalSubtitlesAdded = true
		val stream = currentStream ?: return
		stream.externalSubtitles.forEach { subtitle ->
			val flags = buildString {
				append(if (shouldSelectExternalSubtitle(subtitle, stream.selectedSubtitleStreamIndex)) "select" else "auto")
				if (subtitle.isForced) append("+forced")
				if (subtitle.isDefault) append("+default")
			}
			runCommand(
				"sub-add",
				subtitle.url,
				flags,
				subtitle.title.orEmpty(),
				subtitle.language.orEmpty(),
			)
		}
	}

	private fun refreshTracks() {
		player.getPropertyNode("track-list")?.let(::updateTracks)
	}

	private fun updateTracks(node: MPVNode, notify: Boolean = true) {
		val updatedTracks = parseTracks(node)
		tracks = updatedTracks
		if (notify && updatedTracks != notifiedTracks) {
			notifiedTracks = updatedTracks
			notifyTracksChanged()
		}
	}

	private fun parseTracks(node: MPVNode): List<LibMPVTrack> = node.asArray().orEmpty().mapNotNull { item ->
		val map = item.asMap() ?: return@mapNotNull null
		val type = when (map["type"]?.asString()) {
			"audio" -> TrackType.AUDIO
			"sub" -> TrackType.SUBTITLE
			else -> return@mapNotNull null
		}
		val id = map["id"]?.asInt()?.toInt() ?: return@mapNotNull null
		LibMPVTrack(
			id = id,
			type = type,
			title = map["title"]?.asString(),
			language = map["lang"]?.asString(),
			codec = map["codec"]?.asString(),
			isSelected = map["selected"]?.asBoolean() == true,
			ffIndex = map["ff-index"]?.asInt()?.toInt(),
			isExternal = map["external"]?.asBoolean() == true,
			externalFilename = map["external-filename"]?.asString(),
		)
	}

	private fun applyInitialTrackSelection() {
		val stream = currentStream ?: return
		pendingInitialTrackTypes.toList().forEach { type ->
			val streamIndex = when (type) {
				TrackType.AUDIO -> stream.selectedAudioStreamIndex
				TrackType.SUBTITLE -> stream.selectedSubtitleStreamIndex
			} ?: return@forEach
			if (applyInitialTrackSelection(stream, type, streamIndex)) pendingInitialTrackTypes -= type
		}
	}

	private fun applyInitialTrackSelection(
		stream: PlayableMediaStream,
		type: TrackType,
		streamIndex: Int,
	): Boolean {
		if (type == TrackType.SUBTITLE && streamIndex < 0) {
			setProperty("sid", "no")
			return true
		}

		val track = findTrack(stream, type, streamIndex)
		if (track == null) {
			if (tracks.none { it.type == type }) return false
			Timber.w("Could not find initial %s stream index %d", type.name.lowercase(), streamIndex)
			return true
		}
		setProperty(type.selectionProperty, track.id.toString())
		Timber.i("Applied initial %s stream index %d as MPV track %d", type.name.lowercase(), streamIndex, track.id)
		return true
	}

	private fun findTrack(stream: PlayableMediaStream, type: TrackType, streamIndex: Int): LibMPVTrack? {
		val selectable = tracks.filter { track -> track.type == type }
		selectable.firstOrNull { track -> track.ffIndex == streamIndex }?.let { return it }

		if (type == TrackType.SUBTITLE) {
			val external = stream.externalSubtitles.firstOrNull { subtitle -> subtitle.index == streamIndex }
			if (external != null) {
				selectable.firstOrNull { track ->
					track.isExternal && track.externalFilename.matchesExternalUrl(external.url)
				}?.let { return it }
			}
		}

		val sourceIndex = stream.mpvSourceTracks(type).indexOfFirst { source -> source.index == streamIndex }
		return selectable.getOrNull(sourceIndex)
	}

	override fun getAvailableTracks(type: TrackType): List<PlayerTrack> {
		player.getPropertyNode("track-list")?.let { node -> updateTracks(node, notify = false) }
		val stream = currentStream
		val sourceTracks = stream?.mpvSourceTracks(type).orEmpty()
		val selectable = tracks.filter { track -> track.type == type }
		return selectable.mapIndexed { index, track ->
			val source = stream?.sourceTrackFor(track, index, sourceTracks)
			PlayerTrack(
				index = index,
				type = type,
				label = track.title ?: source.label(),
				language = track.language ?: source.language(),
				codec = track.codec ?: source?.codec,
				isSelected = track.isSelected,
				streamIndex = source?.index ?: track.ffIndex,
				trackIndex = track.id,
			)
		}
	}

	override fun selectTrack(type: TrackType, index: Int): Boolean {
		pendingInitialTrackTypes -= type
		if (currentStream?.conversionMethod != MediaConversionMethod.None) return false
		if (type == TrackType.SUBTITLE && index == -1) {
			setProperty("sid", "no")
			refreshTracks()
			return true
		}
		refreshTracks()
		val track = tracks.filter { candidate -> candidate.type == type }.getOrNull(index) ?: return false
		setProperty(type.selectionProperty, track.id.toString())
		refreshTracks()
		return true
	}

	private fun PlayableMediaStream.sourceTrackFor(
		track: LibMPVTrack,
		ordinal: Int,
		sourceTracks: List<MediaStreamTrack>,
	): MediaStreamTrack? {
		track.ffIndex?.let { index -> sourceTracks.firstOrNull { source -> source.index == index }?.let { return it } }
		if (track.type == TrackType.SUBTITLE && track.isExternal) {
			val external = externalSubtitles.firstOrNull { subtitle ->
				track.externalFilename.matchesExternalUrl(subtitle.url)
			}
			external?.let { subtitle ->
				sourceTracks.firstOrNull { source -> source.index == subtitle.index }?.let { return it }
			}
		}
		return sourceTracks.getOrNull(ordinal)
	}

	private fun setOption(name: String, value: String) {
		runCatching { player.setPropertyString("options/$name", value) }
			.onFailure { error -> Timber.w(error, "Unable to set MPV option %s=%s", name, value) }
	}

	private fun setProperty(name: String, value: String) {
		runCatching { player.setPropertyString(name, value) }
			.onFailure { error -> Timber.w(error, "Unable to set MPV property %s", name) }
	}

	private fun setBooleanProperty(name: String, value: Boolean) {
		runCatching { player.setPropertyBoolean(name, value) }
			.onFailure { error -> Timber.w(error, "Unable to set MPV property %s", name) }
	}

	private fun setDoubleProperty(name: String, value: Double) {
		runCatching { player.setPropertyDouble(name, value) }
			.onFailure { error -> Timber.w(error, "Unable to set MPV property %s", name) }
	}

	private fun runCommand(vararg command: String): Boolean = runCatching {
		player.command(*command)
	}.onFailure { error ->
		Timber.e(error, "MPV command failed: %s", command.firstOrNull())
	}.isSuccess

	private fun onPlayerEvent(generation: Long, block: () -> Unit) {
		if (generation != playerGeneration) return
		val guardedBlock = {
			if (generation == playerGeneration) block()
		}
		if (Looper.myLooper() == Looper.getMainLooper()) guardedBlock() else handler.post(guardedBlock)
	}
}

private val TrackType.selectionProperty: String
	get() = when (this) {
		TrackType.AUDIO -> "aid"
		TrackType.SUBTITLE -> "sid"
	}

private fun MediaStreamTrack?.label(): String? = when (this) {
	is MediaStreamAudioTrack -> title ?: language
	is MediaStreamSubtitleTrack -> title ?: language
	else -> null
}

private fun MediaStreamTrack?.language(): String? = when (this) {
	is MediaStreamAudioTrack -> language
	is MediaStreamSubtitleTrack -> language
	else -> null
}

private fun String?.matchesExternalUrl(url: String): Boolean {
	if (this == null) return false
	return this == url || substringAfterLast('/') == url.substringAfterLast('/')
}

internal fun mpvPositionInfo(
	activeSeconds: Double?,
	durationSeconds: Double?,
	cacheEndSeconds: Double?,
	cacheDurationSeconds: Double?,
	fallback: PositionInfo,
): PositionInfo {
	val activeValue = activeSeconds?.takeIf { it.isFinite() && it >= 0.0 } ?: return fallback
	val active = activeValue.seconds
	val duration = durationSeconds
		?.takeIf { it.isFinite() && it >= 0.0 }
		?.seconds
		?: fallback.duration
	val cacheEnd = cacheEndSeconds?.takeIf { it.isFinite() && it >= 0.0 }?.seconds
	val cacheDuration = cacheDurationSeconds?.takeIf { it.isFinite() && it >= 0.0 }?.seconds
	val buffered = maxOf(
		active,
		when {
			cacheDuration != null -> active + cacheDuration
			cacheEnd != null -> cacheEnd
			else -> fallback.buffer
		},
	).let { value ->
		if (duration > Duration.ZERO) minOf(value, duration) else value
	}
	return PositionInfo(active, buffered, duration)
}

private fun Double.toLibMPVString() = String.format(Locale.US, "%.3f", this)

internal fun mpvHdrMode(gamma: String?, dolbyVisionProfile: Int?, hasHdr10Plus: Boolean): String? = when {
	dolbyVisionProfile != null -> "Dolby Vision (Profile $dolbyVisionProfile)"
	hasHdr10Plus -> "HDR10+"
	gamma == "pq" -> "HDR10"
	gamma == "hlg" -> "HLG"
	gamma == "scrgb" -> "scRGB (HDR)"
	gamma == "v-log" -> "Panasonic V-Log"
	gamma == "s-log1" -> "Sony S-Log1"
	gamma == "s-log2" -> "Sony S-Log2"
	gamma == "st428" -> "DCI ST 428"
	gamma == "bt.1886" -> "SDR (BT.1886)"
	gamma == "srgb" -> "SDR (sRGB)"
	gamma?.startsWith("gamma") == true -> "SDR ($gamma)"
	gamma != null -> gamma
	else -> null
}

internal fun mpvHdrPipeline(
	gamma: String?,
	dolbyVisionProfile: Int?,
	hasHdr10Plus: Boolean,
): String? {
	val source = mpvHdrMode(gamma, dolbyVisionProfile, hasHdr10Plus) ?: return null
	val decoded = mpvHdrMode(gamma, null, hasHdr10Plus)
		?.let { if (gamma == "pq") "$it/PQ" else it }
	return buildList {
		add(source)
		if (decoded != null && decoded != source) add(decoded)
	}.joinToString(" \u2192 ")
}

internal fun mpvGpuApi(currentContext: String?): String? =
	when (currentContext) {
		"android" -> "opengl"
		"androidvk" -> "vulkan"
		else -> null
	}

internal fun mpvSelectionDisplay(requested: String, actual: String?) =
	actual?.let { if (requested != it) "$requested \u2192 $it" else it }

internal fun mpvGpuApiDisplay(
	requested: String,
	actual: String?,
	requestedVersion: String?,
	actualVersion: String?,
): String? {
	actual ?: return null
	fun apiVersion(api: String, version: String?) = listOfNotNull(api, version).joinToString(" ")
	return if (requested != actual) {
		"${apiVersion(requested, requestedVersion)} \u2192 ${apiVersion(actual, actualVersion)}"
	} else {
		apiVersion(actual, actualVersion)
	}
}

internal fun mpvSubtitleMarginY(bottomPaddingFraction: Float) =
	(bottomPaddingFraction * 720f).roundToInt().coerceIn(0, 600)

internal fun mpvSubtitleFontSize(textSizeDp: Float) =
	(textSizeDp * 38f / 24f).coerceIn(8f, 96f)

internal fun Duration?.mpvStartOption() =
	this?.takeIf { it > Duration.ZERO }?.let { "start=${it.inWholeMilliseconds / 1_000.0}" }

internal fun formatLibMPVBufferDetails(
	bufferedBytes: Long?,
	isPausedForCache: Boolean,
	isCacheIdle: Boolean,
	cacheSpeed: Double?,
) = buildList {
	bufferedBytes?.formatBufferBytes()?.let(::add)
	if (isPausedForCache) add("paused") else if (isCacheIdle) add("idle")
	if (!isCacheIdle) {
		cacheSpeed
			?.takeIf { it.isFinite() && it > 0 }
			?.toLong()
			?.formatBufferBytes()
			?.let { add("$it/s") }
	}
}.joinToString(", ").takeIf(String::isNotEmpty)

private fun Int.mpvColor() = String.format(Locale.US, "#%08X", toLong() and 0xFFFF_FFFFL)

private fun Int.alpha() = this ushr 24 and 0xFF
