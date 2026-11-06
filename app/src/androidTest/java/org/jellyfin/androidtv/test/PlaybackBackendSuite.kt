package org.jellyfin.androidtv.test

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.libVLCAudioOutput
import org.jellyfin.androidtv.preference.libVLCDecoder
import org.jellyfin.androidtv.preference.mpvDecoder
import org.jellyfin.androidtv.preference.preferExoPlayerFfmpeg
import org.jellyfin.androidtv.preference.preferExoPlayerFfmpegAudioForLiveTv
import org.jellyfin.androidtv.preference.preferExoPlayerFfmpegVideo
import org.jellyfin.androidtv.preference.preferExoPlayerFfmpegVideoForLiveTv
import org.jellyfin.androidtv.preference.constant.libVLCPlaybackOptions
import org.jellyfin.androidtv.preference.constant.libVLCStartupOptions
import org.jellyfin.androidtv.preference.constant.mpvPlaybackOptions
import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.androidtv.util.DeviceGraphicsInfoProvider
import org.jellyfin.androidtv.util.profile.MediaCodecCapabilitiesTest
import org.jellyfin.androidtv.util.profile.bindDoviPlaybackPlan
import org.jellyfin.androidtv.util.profile.createDeviceProfile
import org.jellyfin.androidtv.util.profile.createDoviPlaybackPlan
import org.jellyfin.androidtv.util.profile.getSupportedDisplayHdrTypes
import org.jellyfin.androidtv.util.profile.retainDoviPlaybackPlanFor
import org.jellyfin.androidtv.util.profile.retainsDoviDecision
import org.jellyfin.playback.dovi.doviDecision
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.backend.TrackSelectionBackend
import org.jellyfin.playback.core.backend.TrackType
import org.jellyfin.playback.core.mediastream.MediaStreamResolver
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PlaybackFrameStats
import org.jellyfin.playback.core.playbackManager
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.plugin.playbackPlugin
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.core.queue.supplier.QueueSupplier
import org.jellyfin.playback.core.ui.PlayerSurfaceView
import org.jellyfin.playback.jellyfin.JellyfinDeviceProfileRequest
import org.jellyfin.playback.jellyfin.mediastream.JellyfinMediaStreamOptions
import org.jellyfin.playback.jellyfin.mediastream.JellyfinMediaStreamResolver
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.forceTranscoding
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.playback.libvlc.LibVLCBackend
import org.jellyfin.playback.libvlc.LibVLCInstanceOptions
import org.jellyfin.playback.media3.exoplayer.ExoPlayerBackend
import org.jellyfin.playback.media3.exoplayer.ExoPlayerOptions
import org.jellyfin.playback.mpv.LibMPVBackend
import org.jellyfin.sdk.model.api.MediaStreamType
import org.koin.core.context.GlobalContext
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PlaybackBackendSuite(
	private val instrumentation: Instrumentation,
	private val activity: Activity,
	private val environment: PlaybackServerEnvironment,
) {
	private val context = instrumentation.targetContext

	fun run(
		backendFilter: String?,
		scenarioFilter: String?,
		transcodingOnly: Boolean = false,
		repeatCount: Int = 1,
		suiteName: String? = null,
		hdmiAudioOnly: Boolean = false,
	): List<PlaybackTestResult> {
		val factories = backendFactories()
		val standardCases = listOf(
			BackendCase("controls", "1080p-sdr-avc", controls = true),
			BackendCase("subtitles", "1080p-sdr-ass", requireSubtitle = true),
			BackendCase("track-persistence", "multi-track", trackPersistence = true),
			BackendCase("hdr10", "4k-hdr10-hevc"),
			BackendCase("dolby-5", "4k-dv5"),
			BackendCase("dolby-7", "4k-dv7"),
			BackendCase("dolby-7-transcode", "4k-dv7", variant = PlaybackProfileVariant.VIDEO_TRANSCODE),
			BackendCase("dolby-8", "4k-dv8"),
		)
		val hdmiSupport = detectHdmiAudioSupport(context).takeIf { hdmiAudioOnly }
		if (hdmiSupport != null && (!hdmiSupport.outputPresent || hdmiSupport.codecs.isEmpty())) {
			return listOf(PlaybackTestResult(PlaybackTestStatus.SKIP, "hdmi-audio", detail = hdmiSupport.detail))
		}
		val cases = if (hdmiSupport != null) {
			PlaybackHdmiAudioMatrix.plan(environment.fixtures.values.map(ServerPlaybackFixture::descriptor), hdmiSupport.codecs).map { hdmiCase ->
				val descriptor = environment.fixtures.getValue(hdmiCase.descriptorId).descriptor
				BackendCase(
					id = hdmiCase.id,
					descriptorId = hdmiCase.descriptorId,
					selectedAudioCodec = descriptor.audioStreams.first { PlaybackHdmiAudioMatrix.codecMatches(hdmiCase.codec, it.codec) }.codec,
					expectedPassthroughCodec = hdmiCase.codec,
				)
			}
		} else if (transcodingOnly) {
			PlaybackTranscodingMatrix.planLivePlayback(environment.selection).map { testCase ->
				BackendCase(
					id = testCase.id,
					descriptorId = testCase.descriptorId,
					variant = testCase.variant,
					selectedAudioCodec = testCase.selectedAudioCodec,
					selectedSubtitleCodec = testCase.selectedSubtitleCodec,
				)
			}
		} else standardCases
		val results = mutableListOf<PlaybackTestResult>()
		if (hdmiSupport != null) {
			val planned = cases.mapNotNull(BackendCase::expectedPassthroughCodec).toSet()
			for (missing in hdmiSupport.codecs - planned) {
				results += PlaybackTestResult(
					PlaybackTestStatus.WARN,
					"hdmi-audio",
					scenario = "passthrough-$missing",
					detail = "HDMI reports $missing but the server test folder has no matching item",
				)
			}
		}
		for ((backendName, factory) in factories) {
			if (backendFilter != null && backendFilter != backendName) continue
			val resources = mutableListOf(resourceSample())
			for (testCase in cases) {
				if (scenarioFilter != null && scenarioFilter != testCase.id && scenarioFilter != testCase.fixtureId) continue
				val descriptor = testCase.descriptorId?.let { environment.fixtures[it]?.descriptor }
					?: testCase.fixtureId?.let(environment.selection.fixtures::get)
				if (descriptor == null) {
					results += PlaybackTestResult(
						PlaybackTestStatus.SKIP,
						if (transcodingOnly) "transcode" else "backend",
						backendName,
						testCase.id,
						"fixture unavailable",
					)
					continue
				}
				val fixture = environment.fixtures.getValue(descriptor.id)
				repeat(repeatCount) { iteration ->
					val scenario = if (repeatCount == 1) testCase.id else "${testCase.id}-${iteration + 1}"
					results += try {
						val detail = runCase(backendName, factory, fixture, testCase)
						PlaybackTestResult(
							if (testCase.expectedPassthroughCodec != null && backendName == "VLC") PlaybackTestStatus.WARN else PlaybackTestStatus.PASS,
							suiteName ?: if (transcodingOnly) "transcode" else "backend",
							backendName,
							scenario,
							detail,
						)
					} catch (error: Throwable) {
						PlaybackTestResult(
							PlaybackTestStatus.FAIL,
							suiteName ?: if (transcodingOnly) "transcode" else "backend",
							backendName,
							scenario,
							error.message ?: error.toString(),
						)
					}
					resources += resourceSample()
				}
			}
			if (repeatCount > 1) {
				val trend = evaluateResourceTrend(resources, maxFileDescriptorGrowth = 12, maxMemoryGrowthKb = 65_536)
				results += PlaybackTestResult(
					if (trend.failures.isEmpty()) PlaybackTestStatus.PASS else PlaybackTestStatus.FAIL,
					suiteName ?: "soak",
					backendName,
					"resources",
					trend.failures.joinToString("; ").ifBlank {
						"fd=${resources.first().openFileDescriptors}->${resources.last().openFileDescriptors} " +
							"pssKb=${resources.first().totalPssKb}->${resources.last().totalPssKb}"
					},
				)
			}
		}
		return results
	}

	private fun backendFactories(): Map<String, () -> PlayerBackend> {
		val koin = GlobalContext.get()
		val preferences = koin.get<UserPreferences>()
		val dataSourceFactory = koin.get<HttpDataSource.Factory>()
		fun exoPlayer(enableLibass: Boolean) = ExoPlayerBackend(
			context,
			ExoPlayerOptions(
				preferFfmpegAudio = { preferences[UserPreferences.preferExoPlayerFfmpeg] },
				preferFfmpegAudioForLiveTv = { preferences[UserPreferences.preferExoPlayerFfmpegAudioForLiveTv] },
				preferFfmpegVideo = { preferences[UserPreferences.preferExoPlayerFfmpegVideo] },
				preferFfmpegVideoForLiveTv = { preferences[UserPreferences.preferExoPlayerFfmpegVideoForLiveTv] },
				enableLibass = enableLibass,
				libassRenderType = preferences[UserPreferences.libassRenderType].assRenderType,
				libassGlyphSize = preferences[UserPreferences.libassGlyphSize].glyphs,
				libassCacheSize = preferences[UserPreferences.libassCacheSize].megabytes,
				libassMaxRenderPixels = preferences[UserPreferences.libassMaxRenderPixels].pixels,
				libassMaxFps = preferences[UserPreferences.libassMaxFps].framesPerSecond,
				parseSubtitlesDuringExtraction = preferences[UserPreferences.exoPlayerParseSubtitlesDuringExtraction],
				enableDebugLogging = preferences[UserPreferences.debuggingEnabled],
				baseDataSourceFactory = dataSourceFactory,
			),
		)
		return linkedMapOf(
			"ExoPlayer" to { exoPlayer(preferences[UserPreferences.assDirectPlay]) },
			"ExoPlayer-Libass" to { exoPlayer(true) },
			"MPV" to {
				LibMPVBackend(
					context = context,
					videoDecoderProvider = { preferences[UserPreferences.mpvDecoder].decoder },
					playbackOptionsProvider = { preferences.mpvPlaybackOptions() },
					gpuApiVersionProvider = { api -> DeviceGraphicsInfoProvider.getNow()?.apiVersion(api) },
				)
			},
			"VLC" to {
				LibVLCBackend(
					context = context,
					instanceOptionsProvider = {
						LibVLCInstanceOptions(
							arguments = preferences.libVLCStartupOptions(),
							audioOutput = preferences[UserPreferences.libVLCAudioOutput].vlcValue,
						)
					},
					videoDecoderProvider = { preferences[UserPreferences.libVLCDecoder].decoder },
					playbackOptionsProvider = { preferences.libVLCPlaybackOptions() },
				)
			},
		)
	}

	private fun runCase(
		backendName: String,
		factory: () -> PlayerBackend,
		fixture: ServerPlaybackFixture,
		testCase: BackendCase,
	): String {
		val (stream, doviPlan) = runBlocking { resolve(fixture, backendName, testCase) }
		instrumentation.sendStatus(0, Bundle().apply {
			val uri = Uri.parse(stream.url)
			val videoOptions = uri.queryParameterNames.filter {
				it.startsWith("hevc-", ignoreCase = true) || it.lowercase() in setOf(
					"videocodec", "videobitrate", "maxwidth", "maxheight", "allowvideostreamcopy", "enableautostreamcopy",
					"subtitlemethod", "subtitlestreamindex", "maxframerate", "framerate", "requirenonanamorphic", "requireavc", "deinterlace",
				)
			}.associateWith(uri::getQueryParameter)
			putString("stream", "NEGOTIATED $backendName/${testCase.id} method=${stream.conversionMethod} " +
				"reasons=${transcodeReasons(stream.url)} requestedRoute=${doviPlan?.decision?.route} " +
				"activeRoute=${stream.queueEntry.doviDecision?.route} options=$videoOptions\n")
		})
		var activeStream = stream
		lateinit var backend: PlayerBackend
		lateinit var manager: PlaybackManager
		lateinit var surfaceView: PlayerSurfaceView
		var createdBackend: PlayerBackend? = null
		var createdManager: PlaybackManager? = null
		var playbackError: String? = null
		var playbackErrorCount = 0
		var mediaEndCount = 0
		var playing = false
		try {
			onMain {
				backend = factory()
				createdBackend = backend
				manager = playbackManager(context) {
					install(playbackPlugin {
						provide(backend)
						provide(object : MediaStreamResolver {
							override suspend fun getStream(queueEntry: QueueEntry, startPosition: kotlin.time.Duration?) = activeStream
						})
					})
				}
				createdManager = manager
				manager.addBackendEventListener(object : PlayerBackendEventListener() {
					override fun onPlayStateChange(state: PlayState) { if (state == PlayState.PLAYING) playing = true }
					override fun onPlaybackError(error: PlaybackError) {
						playbackErrorCount++
						playbackError = error.toString()
					}
					override fun onMediaStreamEnd(mediaStream: PlayableMediaStream) { mediaEndCount++ }
				})
				surfaceView = PlayerSurfaceView(activity).apply { playbackManager = manager }
				activity.setContentView(surfaceView)
			}
			await("video surface", 10_000) { onMain { surfaceView.surface.holder.surface.isValid && activity.hasWindowFocus() } }
			onMain {
				manager.queue.addSupplier(object : QueueSupplier {
					override val size = 1
					override suspend fun getItem(index: Int) = stream.queueEntry.takeIf { index == 0 }
				}, 0)
			}
			await("PLAYING", 35_000) {
				check(playbackError == null) { playbackError.orEmpty() }
				playing
			}
			val started = onMain { backend.getPositionInfo().active.inWholeMilliseconds }
			await("position advance", 12_000) {
				check(playbackError == null) { playbackError.orEmpty() }
				onMain { backend.getPositionInfo().active.inWholeMilliseconds > started + 250 }
			}

			if (testCase.controls) exerciseControls(manager, backend)
			val trackBackend = backend as? TrackSelectionBackend
			val audioTracks = onMain { trackBackend?.getAvailableTracks(TrackType.AUDIO).orEmpty() }
			val subtitleTracks = onMain { trackBackend?.getAvailableTracks(TrackType.SUBTITLE).orEmpty() }
			check(!testCase.requireSubtitle || subtitleTracks.isNotEmpty()) { "No subtitle tracks exposed" }
			var selectedAudioStreamIndex: Int? = null
			var selectedSubtitleStreamIndex: Int? = null
			if (audioTracks.size > 1) {
				val alternative = audioTracks.firstOrNull { !it.isSelected }
				if (alternative != null) {
					check(onMain { trackBackend?.selectTrack(TrackType.AUDIO, alternative.index) } == true) {
						"Alternative audio track could not be selected"
					}
					selectedAudioStreamIndex = alternative.streamIndex
					await("audio selection", 5_000) {
						onMain { trackBackend?.getAvailableTracks(TrackType.AUDIO)?.any { it.streamIndex == alternative.streamIndex && it.isSelected } == true }
					}
				}
			}
			if (testCase.trackPersistence && subtitleTracks.isNotEmpty()) {
				val subtitle = subtitleTracks.firstOrNull { !it.isSelected } ?: subtitleTracks.first()
				check(onMain { trackBackend?.selectTrack(TrackType.SUBTITLE, subtitle.index) } == true) { "Subtitle track could not be selected" }
				selectedSubtitleStreamIndex = subtitle.streamIndex
				await("subtitle selection", 5_000) {
					onMain { trackBackend?.getAvailableTracks(TrackType.SUBTITLE)?.any { it.streamIndex == subtitle.streamIndex && it.isSelected } == true }
				}
			}
			if (testCase.trackPersistence) {
				check(selectedAudioStreamIndex != null || selectedSubtitleStreamIndex != null) { "No alternate audio or subtitle track available" }
				val seekTarget = onMain { backend.getPositionInfo().active + 2.seconds }
				check(onMain { backend.seekTo(seekTarget) }) { "Backend rejected track-persistence seek" }
				awaitTrackSelections(trackBackend, selectedAudioStreamIndex, selectedSubtitleStreamIndex)
				activeStream = stream.copy(
					selectedAudioStreamIndex = selectedAudioStreamIndex ?: stream.selectedAudioStreamIndex,
					selectedSubtitleStreamIndex = selectedSubtitleStreamIndex ?: stream.selectedSubtitleStreamIndex,
				)
				runBlocking { withContext(Dispatchers.Main) { check(manager.reloadCurrentMediaStream(seekTarget)) } }
				await("track reload", 30_000) { onMain { backend.getPositionInfo().active >= seekTarget - 500.milliseconds } }
				awaitTrackSelections(trackBackend, selectedAudioStreamIndex, selectedSubtitleStreamIndex)
			}
			val healthBefore = onMain { backend.getFrameStats().healthSample() }
			SystemClock.sleep(1_500)
			val stats = onMain { backend.getFrameStats() }
			val health = evaluatePlaybackHealth(
				healthBefore,
				stats.healthSample(),
				requireAudioDecoder = fixture.descriptor.audioStreamCount > 0,
			)
			instrumentation.sendStatus(0, Bundle().apply {
				putString("stream", "OBSERVED $backendName/${testCase.id} positionMs=" +
					onMain { backend.getPositionInfo().active.inWholeMilliseconds } +
					" frames=${stats.videoDecodedFrames}/${stats.droppedFrames} dovi=${stats.doviTransform}\n")
			})
			check(health.failures.isEmpty()) { health.failures.joinToString("; ") }
			testCase.expectedPassthroughCodec?.let { expectedCodec ->
				check(stream.conversionMethod != org.jellyfin.playback.core.mediastream.MediaConversionMethod.Transcode) {
					"Audio was transcoded instead of sent directly"
				}
				if (backendName != "VLC") check(stats.audioPassthroughSupported == true) {
					"$expectedCodec passthrough was not active (reported=${stats.audioPassthroughSupported})"
				}
				if (stats.audioCodec != null) check(PlaybackHdmiAudioMatrix.codecMatches(expectedCodec, stats.audioCodec)) {
					"Expected $expectedCodec audio, got ${stats.audioCodec}"
				}
			}
			check(playbackErrorCount <= 1) { "Duplicate playback errors: $playbackErrorCount" }
			check(mediaEndCount == 0) { "Unexpected media-end events: $mediaEndCount" }
			return "method=${stream.conversionMethod} tracks=${audioTracks.size}/${subtitleTracks.size} " +
				"frames=${stats.videoDecodedFrames}/${stats.droppedFrames} decoder=${stats.videoDecoderName}/${stats.videoDecoderType} " +
				"audioDecoder=${stats.audioDecoderName}/${stats.audioDecoderType} codec=${stats.videoCodec} range=${stats.videoRange} " +
				"requestedDoviRoute=${doviPlan?.decision?.route}/${doviPlan?.decision?.reason} " +
				"activeDoviRoute=${activeStream.queueEntry.doviDecision?.route} " +
				"doviTransform=${stats.doviTransform} libass=${stats.libass?.renderCount}"
		} finally {
			if (createdManager != null || createdBackend != null) {
				onMain {
					createdManager?.let { activeManager ->
						activeManager.state.stop()
						while (true) activeManager.removeService(activeManager.getService<PlayerService>() ?: break)
					}
					createdBackend?.release()
					activity.setContentView(FrameLayout(activity))
				}
			}
			if (stream.conversionMethod != org.jellyfin.playback.core.mediastream.MediaConversionMethod.None) {
				runBlocking { environment.session.stopEncoding(stream.identifier) }
			}
		}
	}

	private fun awaitTrackSelections(
		backend: TrackSelectionBackend?,
		audioStreamIndex: Int?,
		subtitleStreamIndex: Int?,
	) {
		audioStreamIndex?.let { expected ->
			await("audio selection persistence", 8_000) {
				onMain { backend?.getAvailableTracks(TrackType.AUDIO)?.any { it.streamIndex == expected && it.isSelected } == true }
			}
		}
		subtitleStreamIndex?.let { expected ->
			await("subtitle selection persistence", 8_000) {
				onMain { backend?.getAvailableTracks(TrackType.SUBTITLE)?.any { it.streamIndex == expected && it.isSelected } == true }
			}
		}
	}

	private fun exerciseControls(manager: PlaybackManager, backend: PlayerBackend) {
		onMain { manager.state.pause() }
		SystemClock.sleep(350)
		val paused = onMain { backend.getPositionInfo().active.inWholeMilliseconds }
		SystemClock.sleep(350)
		check(abs(onMain { backend.getPositionInfo().active.inWholeMilliseconds } - paused) <= 180) { "Position moved while paused" }
		check(onMain { backend.seekTo(5.seconds) }) { "Backend rejected an absolute seek" }
		onMain { manager.state.unpause() }
		await("seek and resume", 12_000) { onMain { backend.getPositionInfo().active.inWholeMilliseconds } in 4_000..8_000 }
		runBlocking { withContext(Dispatchers.Main) { check(manager.reloadCurrentMediaStream(1_500.milliseconds)) } }
		await("reload", 30_000) { onMain { backend.getPositionInfo().active.inWholeMilliseconds } in 1_250..4_000 }
	}

	private suspend fun resolve(
		fixture: ServerPlaybackFixture,
		backendName: String,
		testCase: BackendCase,
	): Pair<PlayableMediaStream, org.jellyfin.androidtv.util.profile.DoviPlaybackPlan?> {
		val (production, plan) = createProductionProfile(backendName, fixture)
		val configured = testCase.variant.configure(production, fixture.descriptor.container)
		val streams = fixture.source.mediaStreams.orEmpty()
		val audioIndex = testCase.selectedAudioCodec?.let { codec ->
			streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.codec.equals(codec, true) }?.index
		}
		val subtitleIndex = testCase.selectedSubtitleCodec?.let { codec ->
			streams.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.codec.equals(codec, true) }?.index
		}
		check(testCase.selectedAudioCodec == null || audioIndex != null) { "Selected audio stream is unavailable" }
		check(testCase.selectedSubtitleCodec == null || subtitleIndex != null) { "Selected subtitle stream is unavailable" }
		val entry = QueueEntry().apply {
			baseItem = fixture.item
			mediaSourceId = fixture.source.id
			forceTranscoding = configured.forceTranscoding
			bindDoviPlaybackPlan(plan)
		}
		val stream = requireNotNull(
			JellyfinMediaStreamResolver(
				environment.session.testApi,
				{ JellyfinDeviceProfileRequest(configured.profile, 0, protectsDoviHlsVideoCopy = backendName.startsWith("ExoPlayer")) },
				mediaStreamOptionsProvider = { _, _ ->
					JellyfinMediaStreamOptions(
						audioStreamIndex = audioIndex,
						subtitleStreamIndex = subtitleIndex,
						alwaysBurnInSubtitleWhenTranscoding = configured.burnSubtitles,
					)
				},
				doviDecisionValidator = { queued, _, source, method, expected ->
					if (source != null && method.retainsDoviDecision()) queued.retainDoviPlaybackPlanFor(plan, source, expected)
					else queued.bindDoviPlaybackPlan(null)
				},
			).getStream(entry, null)
		) { "Server returned no playable stream" }
		check(stream.conversionMethod in configured.expectedMethods) {
			"Expected ${configured.expectedMethods}, got ${stream.conversionMethod}"
		}
		if (configured.forceTranscoding) check(stream.queueEntry.doviDecision == null) {
			"Forced video transcoding retained a local Dolby transform"
		}
		val requiredReason = when (testCase.variant) {
			PlaybackProfileVariant.AUDIO_TRANSCODE -> "AudioCodecNotSupported"
			PlaybackProfileVariant.SUBTITLE_TRANSCODE -> "SubtitleCodecNotSupported"
			else -> null
		}
		check(requiredReason == null || requiredReason in transcodeReasons(stream.url)) {
			"Expected reason $requiredReason, got ${transcodeReasons(stream.url)}"
		}
		return stream to plan
	}

	private fun createProductionProfile(
		backendName: String,
		fixture: ServerPlaybackFixture,
	): Pair<org.jellyfin.sdk.model.api.DeviceProfile, org.jellyfin.androidtv.util.profile.DoviPlaybackPlan?> {
		val koin = GlobalContext.get()
		val preferences = koin.get<UserPreferences>()
		val mediaTest = MediaCodecCapabilitiesTest(preferences[UserPreferences.softwareCodecsEnabled])
		val backend = when (backendName) {
			"MPV" -> PlaybackBackend.MPV
			"VLC" -> PlaybackBackend.LIBVLC
			else -> PlaybackBackend.EXOPLAYER
		}
		val plan = createDoviPlaybackPlan(
			item = fixture.item,
			mediaSourceId = fixture.source.id,
			userPreferences = preferences,
			mediaTest = mediaTest,
			retrySuppressed = false,
			displayHdrTypes = getSupportedDisplayHdrTypes(context),
			backendOverride = backend,
		)
		val profile = createDeviceProfile(
			context,
			preferences,
			requireNotNull(koin.get<ServerRepository>().currentServer.value?.serverVersion),
			doviPlaybackPlan = plan,
			mediaTest = mediaTest,
		)
		return profile to plan
	}

	private fun await(label: String, timeoutMs: Long, condition: () -> Boolean) {
		val deadline = SystemClock.elapsedRealtime() + timeoutMs
		while (SystemClock.elapsedRealtime() < deadline) {
			if (condition()) return
			SystemClock.sleep(100)
		}
		error("Timed out waiting for $label")
	}

	private fun <T> onMain(action: () -> T): T {
		var result: Result<T>? = null
		instrumentation.runOnMainSync { result = runCatching(action) }
		return requireNotNull(result).getOrThrow()
	}

	private fun resourceSample(): PlaybackResourceSample {
		val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
		return PlaybackResourceSample(
			openFileDescriptors = java.io.File("/proc/self/fd").list()?.size ?: -1,
			totalPssKb = memory.totalPss,
		)
	}
}

private data class BackendCase(
	val id: String,
	val fixtureId: String? = null,
	val descriptorId: String? = null,
	val variant: PlaybackProfileVariant = PlaybackProfileVariant.PRODUCTION,
	val selectedAudioCodec: String? = null,
	val selectedSubtitleCodec: String? = null,
	val controls: Boolean = false,
	val requireSubtitle: Boolean = false,
	val trackPersistence: Boolean = false,
	val expectedPassthroughCodec: String? = null,
)

private fun PlaybackFrameStats.healthSample() = PlaybackHealthSample(videoDecodedFrames, droppedFrames, audioDecoderName)
