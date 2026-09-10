package org.jellyfin.androidtv.test

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import androidx.media3.datasource.DefaultDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.createPlaybackErrorOrigin
import org.jellyfin.playback.core.backend.matches
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamContainer
import org.jellyfin.playback.core.mediastream.MediaStreamResolver
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.startPosition
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.playbackManager
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.plugin.playbackPlugin
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.core.queue.supplier.QueueSupplier
import org.jellyfin.playback.core.ui.PlayerSurfaceView
import org.jellyfin.playback.libvlc.LibVLCBackend
import org.jellyfin.playback.jellyfin.livetv.LiveTvPlaybackPolicy
import org.jellyfin.playback.jellyfin.recovery.NetworkPlaybackRecoveryService
import org.jellyfin.playback.media3.exoplayer.ExoPlayerBackend
import org.jellyfin.playback.media3.exoplayer.ExoPlayerOptions
import org.jellyfin.playback.mpv.LibMPVBackend
import java.io.File
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Runs the real queue and native backends against a local video, without changing server progress. */
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
class PlaybackTestInstrumentation : Instrumentation() {
	private lateinit var activity: Activity
	private lateinit var video: File
	private lateinit var arguments: PlaybackTestArguments
	private var serverEnvironment: PlaybackServerEnvironment? = null

	override fun onCreate(arguments: Bundle?) {
		super.onCreate(arguments)
		this.arguments = PlaybackTestArguments.from(
			listOf("suite", "backend", "scenario", "testUser", "testFolder", "soakIterations").associateWith { arguments?.getString(it) }
		)
		start()
	}

	override fun onStart() {
		if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
		val results = mutableListOf<PlaybackTestResult>()
		try {
			if (arguments.suites.any { it == "resume" || it == "recovery" }) {
				video = File(targetContext.cacheDir, "resume-test.mp4")
				context.assets.open("silent-black-25s.mp4").use { input -> video.outputStream().use(input::copyTo) }
				activity = startActivitySync(Intent(targetContext, PlaybackTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
			}
			if ("resume" in arguments.suites) {
				val factories = linkedMapOf<String, () -> PlayerBackend>(
					"ExoPlayer" to { ExoPlayerBackend(targetContext, ExoPlayerOptions(baseDataSourceFactory = DefaultDataSource.Factory(targetContext))) },
					"ExoPlayer-Libass" to {
						ExoPlayerBackend(targetContext, ExoPlayerOptions(
							enableLibass = true,
							baseDataSourceFactory = DefaultDataSource.Factory(targetContext),
						))
					},
					"MPV" to { LibMPVBackend(targetContext) },
					"VLC" to { LibVLCBackend(targetContext) },
				)
				for ((name, factory) in factories) {
					if (arguments.backend != null && arguments.backend != name) continue
					var sharedBackend: PlayerBackend? = null
					val sharedFactory = { sharedBackend ?: factory().also { sharedBackend = it } }
					try {
						for ((scenario, requested) in listOf(
						"resume" to 15_250L,
						"restart" to 0L,
						"fresh" to null,
						"preroll" to -500L,
						"preloaded" to 15_250L,
						"reload" to 15_250L,
						)) {
							if (arguments.scenario != null && arguments.scenario != scenario) continue
							val label = "$name/$scenario"
							try {
								val observed = checkStart(sharedFactory, requested, scenario)
								results += PlaybackTestResult(PlaybackTestStatus.PASS, "resume", name, scenario, "firstPlayingMs=$observed")
							} catch (error: Throwable) {
								results += PlaybackTestResult(PlaybackTestStatus.FAIL, "resume", name, scenario, error.message ?: error.toString())
								Log.e("ResumeTest", label, error)
							}
							Log.i("ResumeTest", results.last().line())
							sendStatus(0, Bundle().apply { putString("stream", results.last().line() + "\n") })
						}
					} finally {
						onMain { sharedBackend?.release() }
					}
				}
			}
			if ("recovery" in arguments.suites) {
				val factories = linkedMapOf<String, () -> PlayerBackend>(
					"ExoPlayer" to { ExoPlayerBackend(targetContext, ExoPlayerOptions(baseDataSourceFactory = DefaultDataSource.Factory(targetContext))) },
					"ExoPlayer-Libass" to {
						ExoPlayerBackend(targetContext, ExoPlayerOptions(enableLibass = true, baseDataSourceFactory = DefaultDataSource.Factory(targetContext)))
					},
					"MPV" to { LibMPVBackend(targetContext) },
					"VLC" to { LibVLCBackend(targetContext) },
				)
				for ((name, factory) in factories) {
					if (arguments.backend != null && arguments.backend != name) continue
					FaultingPlaybackHttpServer(video).use { server ->
						try {
							val first = checkStart(factory, 5_000L, "recovery", server.url, server)
							check(server.failureInjected) { "The temporary HTTP failure was not exercised" }
							results += PlaybackTestResult(
								PlaybackTestStatus.PASS,
								"recovery",
								name,
								"temporary-http",
								"firstPlayingMs=$first requests=${server.requestCount}",
							)
						} catch (error: Throwable) {
							results += PlaybackTestResult(
								PlaybackTestStatus.FAIL,
								"recovery",
								name,
								"temporary-http",
								error.message ?: error.toString(),
							)
						}
					}
				}
			}
			if (arguments.suites.any { it in setOf("server", "transcode", "backend", "soak", "hdmi-audio") }) {
				runServerCatalog(results)
			}
			if ("server" in arguments.suites) {
				results += runBlocking {
					PlaybackNegotiationSuite(targetContext, requireNotNull(serverEnvironment)).run(arguments.scenario)
				}
			}
			if ("transcode" in arguments.suites) {
				if (!::activity.isInitialized) {
					activity = startActivitySync(Intent(targetContext, PlaybackTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
				}
				results += runBlocking {
					PlaybackNegotiationSuite(targetContext, requireNotNull(serverEnvironment)).run(
						arguments.scenario,
						transcodingOnly = true,
					)
				}
				results += PlaybackBackendSuite(this, activity, requireNotNull(serverEnvironment)).run(
					arguments.backend,
					arguments.scenario,
					transcodingOnly = true,
				)
			}
			if ("backend" in arguments.suites) {
				if (!::activity.isInitialized) {
					activity = startActivitySync(Intent(targetContext, PlaybackTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
				}
				results += PlaybackBackendSuite(this, activity, requireNotNull(serverEnvironment))
					.run(arguments.backend, arguments.scenario)
			}
			if ("soak" in arguments.suites) {
				if (!::activity.isInitialized) {
					activity = startActivitySync(Intent(targetContext, PlaybackTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
				}
				results += PlaybackBackendSuite(this, activity, requireNotNull(serverEnvironment)).run(
					backendFilter = arguments.backend,
					scenarioFilter = "controls",
					repeatCount = arguments.soakIterations,
					suiteName = "soak",
				)
			}
			if ("hdmi-audio" in arguments.suites) {
				if (!::activity.isInitialized) {
					activity = startActivitySync(Intent(targetContext, PlaybackTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
				}
				results += PlaybackBackendSuite(this, activity, requireNotNull(serverEnvironment)).run(
					backendFilter = arguments.backend,
					scenarioFilter = arguments.scenario,
					suiteName = "hdmi-audio",
					hdmiAudioOnly = true,
				)
			}
		} catch (error: Throwable) {
			results += PlaybackTestResult(PlaybackTestStatus.FAIL, "runner", detail = error.toString())
			Log.e("ResumeTest", "runner", error)
		} finally {
			serverEnvironment?.let { environment -> appendWatchedStateResult(results, environment) }
			if (::activity.isInitialized) onMain { activity.finish() }
			if (::video.isInitialized) video.delete()
		}
		finishWith(results)
	}

	private fun runServerCatalog(results: MutableList<PlaybackTestResult>) = runBlocking {
		val environment = PlaybackServerSession.connect(arguments.testUser).loadEnvironment(arguments.testFolder)
		serverEnvironment = environment
		results += PlaybackTestResult(
			PlaybackTestStatus.PASS,
			"server",
			detail = "user=${arguments.testUser} folder=${arguments.testFolder} items=${environment.fixtures.size} coverage=${environment.selection.coverage}",
		)
		for (warning in environment.selection.warnings) {
			val scenario = warning.substringAfter(' ')
			if (arguments.scenario == null || arguments.scenario == scenario) {
				results += PlaybackTestResult(PlaybackTestStatus.WARN, "server", scenario = scenario, detail = warning)
			}
		}
	}

	private fun appendWatchedStateResult(
		results: MutableList<PlaybackTestResult>,
		environment: PlaybackServerEnvironment,
	) = runBlocking {
		val differences = environment.watchedDifferences()
		if (differences.isEmpty()) {
			results += PlaybackTestResult(PlaybackTestStatus.PASS, "server", scenario = "watched-state", detail = "unchanged")
		} else {
			results += differences.map { PlaybackTestResult(PlaybackTestStatus.FAIL, "server", scenario = "watched-state", detail = it) }
		}
	}

	private fun finishWith(results: List<PlaybackTestResult>) {
		val summary = PlaybackTestSummary(results)
		val reportDirectory = targetContext.getExternalFilesDir("playback-tests")
		if (reportDirectory != null) {
			reportDirectory.mkdirs()
			File(reportDirectory, "latest.json").writeText(summary.toJson())
			serverEnvironment?.let { environment ->
				File(reportDirectory, "catalog.json").writeText(
					playbackMediaCatalogJson(environment.fixtures.values.map(ServerPlaybackFixture::descriptor))
				)
			}
		}
		finish(if (summary.failed) Activity.RESULT_CANCELED else Activity.RESULT_OK, Bundle().apply {
			putString("stream", results.joinToString("\n", postfix = "\ncounts=${summary.counts}\n", transform = PlaybackTestResult::line))
		})
	}

	private fun onMain(action: () -> Unit) {
		var failure: Throwable? = null
		runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
		failure?.let { throw it }
	}

	private fun streamFor(entry: QueueEntry, url: String = Uri.fromFile(video).toString()) = PlayableMediaStream(
		identifier = "resume-test",
		conversionMethod = MediaConversionMethod.None,
		container = MediaStreamContainer("mp4"),
		tracks = emptyList(),
		queueEntry = entry,
		url = url,
		errorOrigin = entry.createPlaybackErrorOrigin(url),
	)

	private fun checkStart(
		factory: () -> PlayerBackend,
		requested: Long?,
		scenario: String,
		streamUrl: String? = null,
		faultServer: FaultingPlaybackHttpServer? = null,
	): Long {
		lateinit var backend: PlayerBackend
		lateinit var manager: PlaybackManager
		lateinit var surfaceView: PlayerSurfaceView
		var createdBackend: PlayerBackend? = null
		var createdManager: PlaybackManager? = null
		var firstPlaying: Long? = null
		var playbackError: String? = null
		var playbackErrorCount = 0
		val traceStarted = SystemClock.elapsedRealtime()
		val playbackTrace = mutableListOf<String>()
		var resolveCount = 0
		var expected = (requested ?: 0).coerceAtLeast(0)
		val warm = scenario == "preloaded" || scenario == "reload"
		val entry = QueueEntry().apply { startPosition = if (warm) Duration.ZERO else requested?.milliseconds }
		val nextEntry = QueueEntry().apply { startPosition = requested?.milliseconds }
		try {
			onMain {
				backend = factory()
				createdBackend = backend
				manager = playbackManager(targetContext) {
					install(playbackPlugin {
						provide(backend)
						if (faultServer != null) provide(NetworkPlaybackRecoveryService(LiveTvPlaybackPolicy { false }))
						provide(object : MediaStreamResolver {
							override suspend fun getStream(queueEntry: QueueEntry, startPosition: Duration?): PlayableMediaStream {
								val baseUrl = streamUrl ?: Uri.fromFile(video).toString()
								val resolvedUrl = if (faultServer == null) baseUrl else "$baseUrl?load=${resolveCount++}"
								return streamFor(queueEntry, resolvedUrl)
							}
						})
					})
				}
				createdManager = manager
				manager.addBackendEventListener(object : PlayerBackendEventListener() {
					override fun onPlayStateChange(state: PlayState) {
						playbackTrace += "${SystemClock.elapsedRealtime() - traceStarted}ms:$state@${backend.getPositionInfo().active.inWholeMilliseconds}ms"
						if (state == PlayState.PLAYING && firstPlaying == null) firstPlaying = backend.getPositionInfo().active.inWholeMilliseconds
					}
					override fun onPlaybackError(error: PlaybackError) {
						playbackErrorCount++
						playbackError = "${error.codeName} originMatches=${error.origin?.matches(entry)} state=${manager.state.playState.value}"
					}
				})
				surfaceView = PlayerSurfaceView(activity).apply { playbackManager = manager }
				activity.setContentView(surfaceView)
			}
			val surfaceDeadline = SystemClock.elapsedRealtime() + 10_000
			var surfaceReady = false
			while (!surfaceReady && SystemClock.elapsedRealtime() < surfaceDeadline) {
				onMain { surfaceReady = surfaceView.surface.holder.surface.isValid && activity.hasWindowFocus() }
				if (!surfaceReady) SystemClock.sleep(100)
			}
			check(surfaceReady) { "Video surface not ready; wake the device and dismiss its screensaver" }
			onMain {
				manager.queue.addSupplier(object : QueueSupplier {
					override val size = if (scenario == "preloaded") 2 else 1
					override suspend fun getItem(index: Int) = when (index) {
						0 -> entry
						1 -> nextEntry.takeIf { size == 2 }
						else -> null
					}
				}, startIndex = 0)
			}
			fun awaitPlaying() {
				val timeoutSeconds = if (faultServer == null) 30 else 75
				val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1_000L
				while (SystemClock.elapsedRealtime() < deadline) {
					var ready = false
					onMain {
						if (faultServer == null) check(playbackError == null) { playbackError.orEmpty() }
						ready = firstPlaying != null
					}
					if (ready) break
					SystemClock.sleep(100)
				}
				checkNotNull(firstPlaying) {
					"No PLAYING event within $timeoutSeconds seconds; trace=$playbackTrace errors=$playbackErrorCount lastError=$playbackError"
				}
			}
			awaitPlaying()
			if (faultServer != null) {
				// Let the recovery monitor observe successful playback before simulating a mid-stream outage.
				SystemClock.sleep(7_500)
				var beforeFailure = 0L
				onMain { beforeFailure = backend.getPositionInfo().active.inWholeMilliseconds }
				faultServer.armFailure()
				firstPlaying = null
				runBlocking { withContext(Dispatchers.Main) { check(manager.reloadCurrentMediaStream(beforeFailure.milliseconds)) } }
				awaitPlaying()
				val recovered = checkNotNull(firstPlaying)
				check(faultServer.failureInjected) { "The temporary HTTP failure was not exercised" }
				check(playbackErrorCount <= 1) { "Duplicate backend errors: $playbackErrorCount" }
				check(recovered >= beforeFailure - 250) { "Recovery reset position from $beforeFailure to $recovered ms" }
				check(recovered <= beforeFailure + 2_500) { "Recovery skipped ahead from $beforeFailure to $recovered ms" }
				expected = beforeFailure
			}
			if (warm) {
				onMain {
					manager.state.pause()
					firstPlaying = null
					if (scenario == "preloaded") {
						nextEntry.mediaStream = streamFor(nextEntry, streamUrl ?: Uri.fromFile(video).toString())
						backend.prepareItem(nextEntry)
					}
				}
				runBlocking {
					withContext(Dispatchers.Main) {
						if (scenario == "preloaded") manager.queue.next()
						else check(manager.reloadCurrentMediaStream(requested?.milliseconds))
					}
				}
				awaitPlaying()
			}
			val first = checkNotNull(firstPlaying) { "No PLAYING event within 30 seconds" }
			val startTolerance = if (faultServer == null) 1_500 else 2_500
			check(first in expected - 250..expected + startTolerance) { "Expected first playback near $expected ms, got $first ms" }
			fun awaitAdvance(from: Long) {
				val deadline = SystemClock.elapsedRealtime() + 10_000
				var position = from
				while (SystemClock.elapsedRealtime() < deadline) {
					onMain {
						if (faultServer == null) check(playbackError == null) { playbackError.orEmpty() }
						position = backend.getPositionInfo().active.inWholeMilliseconds
					}
					check(position >= expected - 250) { "Playback reset from $expected to $position ms" }
					if (position > from + 250) return
					SystemClock.sleep(100)
				}
				error("Playback did not advance from $from ms: $position ms (firstPlayingMs=$first)")
			}
			awaitAdvance(expected)
			onMain { check(backend.getPositionInfo().duration.inWholeMilliseconds in 24_900..25_100) { "Original media duration was not retained" } }
			onMain { manager.state.pause() }
			SystemClock.sleep(300)
			var paused = 0L
			onMain { paused = backend.getPositionInfo().active.inWholeMilliseconds }
			SystemClock.sleep(300)
			onMain {
				check(kotlin.math.abs(backend.getPositionInfo().active.inWholeMilliseconds - paused) <= 150) { "Position moved while paused" }
				manager.state.unpause()
			}
			awaitAdvance(paused)
			return first
		} finally {
			onMain {
				createdManager?.let { active ->
					active.state.stop()
					while (true) active.removeService(active.getService<PlayerService>() ?: break)
				}
				createdBackend?.cleanup()
				activity.setContentView(FrameLayout(activity))
			}
		}
	}
}
