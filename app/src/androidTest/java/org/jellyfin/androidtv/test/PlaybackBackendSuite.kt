package org.jellyfin.androidtv.test

import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.media3.datasource.DefaultDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.androidtv.util.profile.MediaCodecCapabilitiesTest
import org.jellyfin.androidtv.util.profile.bindDoviPlaybackPlan
import org.jellyfin.androidtv.util.profile.createDeviceProfile
import org.jellyfin.androidtv.util.profile.createDoviPlaybackPlan
import org.jellyfin.androidtv.util.profile.getSupportedDisplayHdrTypes
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.backend.TrackSelectionBackend
import org.jellyfin.playback.core.backend.TrackType
import org.jellyfin.playback.core.mediastream.MediaStreamResolver
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.model.PlayState
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

	fun run(backendFilter: String?, scenarioFilter: String?, transcodingOnly: Boolean = false): List<PlaybackTestResult> {
		val factories = linkedMapOf<String, () -> PlayerBackend>(
			"ExoPlayer" to { ExoPlayerBackend(context, ExoPlayerOptions(baseDataSourceFactory = DefaultDataSource.Factory(context))) },
			"ExoPlayer-Libass" to {
				ExoPlayerBackend(context, ExoPlayerOptions(enableLibass = true, baseDataSourceFactory = DefaultDataSource.Factory(context)))
			},
			"MPV" to { LibMPVBackend(context) },
			"VLC" to { LibVLCBackend(context) },
		)
		val standardCases = listOf(
			BackendCase("controls", "1080p-sdr-avc", controls = true),
			BackendCase("subtitles", "1080p-sdr-ass", requireSubtitle = true),
			BackendCase("hdr10", "4k-hdr10-hevc"),
			BackendCase("dolby-5", "4k-dv5"),
			BackendCase("dolby-7", "4k-dv7"),
			BackendCase("dolby-8", "4k-dv8"),
		)
		val cases = if (transcodingOnly) {
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
		for ((backendName, factory) in factories) {
			if (backendFilter != null && backendFilter != backendName) continue
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
				results += try {
					val detail = runCase(backendName, factory, fixture, testCase)
					PlaybackTestResult(
						PlaybackTestStatus.PASS,
						if (transcodingOnly) "transcode" else "backend",
						backendName,
						testCase.id,
						detail,
					)
				} catch (error: Throwable) {
					PlaybackTestResult(
						PlaybackTestStatus.FAIL,
						if (transcodingOnly) "transcode" else "backend",
						backendName,
						testCase.id,
						error.message ?: error.toString(),
					)
				}
			}
		}
		return results
	}

	private fun runCase(
		backendName: String,
		factory: () -> PlayerBackend,
		fixture: ServerPlaybackFixture,
		testCase: BackendCase,
	): String {
		val (stream, doviPlan) = runBlocking { resolve(fixture, backendName, testCase) }
		lateinit var backend: PlayerBackend
		lateinit var manager: PlaybackManager
		lateinit var surfaceView: PlayerSurfaceView
		var createdBackend: PlayerBackend? = null
		var createdManager: PlaybackManager? = null
		var playbackError: String? = null
		var playing = false
		try {
			onMain {
				backend = factory()
				createdBackend = backend
				manager = playbackManager(context) {
					install(playbackPlugin {
						provide(backend)
						provide(object : MediaStreamResolver {
							override suspend fun getStream(queueEntry: QueueEntry, startPosition: kotlin.time.Duration?) = stream
						})
					})
				}
				createdManager = manager
				manager.addBackendEventListener(object : PlayerBackendEventListener() {
					override fun onPlayStateChange(state: PlayState) { if (state == PlayState.PLAYING) playing = true }
					override fun onPlaybackError(error: PlaybackError) { playbackError = error.toString() }
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
			if (audioTracks.size > 1) {
				val alternative = audioTracks.firstOrNull { !it.isSelected }
				if (alternative != null) check(onMain { trackBackend?.selectTrack(TrackType.AUDIO, alternative.index) } == true) {
					"Alternative audio track could not be selected"
				}
			}
			val stats = onMain { backend.getFrameStats() }
			return "method=${stream.conversionMethod} tracks=${audioTracks.size}/${subtitleTracks.size} " +
				"decoder=${stats.videoDecoderName}/${stats.videoDecoderType} codec=${stats.videoCodec} range=${stats.videoRange} " +
				"doviRoute=${doviPlan?.decision?.route}/${doviPlan?.decision?.reason} " +
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
				{ JellyfinDeviceProfileRequest(configured.profile, 0) },
				mediaStreamOptionsProvider = { _, _ ->
					JellyfinMediaStreamOptions(
						audioStreamIndex = audioIndex,
						subtitleStreamIndex = subtitleIndex,
						alwaysBurnInSubtitleWhenTranscoding = configured.burnSubtitles,
					)
				},
			).getStream(entry, null)
		) { "Server returned no playable stream" }
		check(stream.conversionMethod in configured.expectedMethods) {
			"Expected ${configured.expectedMethods}, got ${stream.conversionMethod}"
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
)
