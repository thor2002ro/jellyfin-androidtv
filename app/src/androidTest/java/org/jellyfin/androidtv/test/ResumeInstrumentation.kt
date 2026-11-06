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
			listOf("suite", "backend", "scenario", "testUser", "testFolder").associateWith { arguments?.getString(it) }
		)
		start()
	}

	override fun onStart() {
		if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
		val results = mutableListOf<PlaybackTestResult>()
		try {
			if ("resume" in arguments.suites) {
				video = File(targetContext.cacheDir, "resume-test.mp4")
				context.assets.open("silent-black-25s.mp4").use { input -> video.outputStream().use(input::copyTo) }
				activity = startActivitySync(Intent(targetContext, PlaybackTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
			if ("server" in arguments.suites || "transcode" in arguments.suites || "backend" in arguments.suites) {
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

	private fun streamFor(entry: QueueEntry) = PlayableMediaStream(
		identifier = "resume-test",
		conversionMethod = MediaConversionMethod.None,
		container = MediaStreamContainer("mp4"),
		tracks = emptyList(),
		queueEntry = entry,
		url = Uri.fromFile(video).toString(),
	)

	private fun checkStart(factory: () -> PlayerBackend, requested: Long?, scenario: String): Long {
		lateinit var backend: PlayerBackend
		lateinit var manager: PlaybackManager
		lateinit var surfaceView: PlayerSurfaceView
		var createdBackend: PlayerBackend? = null
		var createdManager: PlaybackManager? = null
		var firstPlaying: Long? = null
		var playbackError: String? = null
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
						provide(object : MediaStreamResolver {
							override suspend fun getStream(queueEntry: QueueEntry, startPosition: Duration?) = streamFor(queueEntry)
						})
					})
				}
				createdManager = manager
				manager.addBackendEventListener(object : PlayerBackendEventListener() {
					override fun onPlayStateChange(state: PlayState) {
						if (state == PlayState.PLAYING && firstPlaying == null) firstPlaying = backend.getPositionInfo().active.inWholeMilliseconds
					}
					override fun onPlaybackError(error: PlaybackError) { playbackError = error.toString() }
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
				val deadline = SystemClock.elapsedRealtime() + 30_000
				while (SystemClock.elapsedRealtime() < deadline) {
					var ready = false
					onMain {
						check(playbackError == null) { playbackError.orEmpty() }
						ready = firstPlaying != null
					}
					if (ready) break
					SystemClock.sleep(100)
				}
				checkNotNull(firstPlaying) { "No PLAYING event within 30 seconds" }
			}
			awaitPlaying()
			if (warm) {
				onMain {
					manager.state.pause()
					firstPlaying = null
					if (scenario == "preloaded") {
						nextEntry.mediaStream = streamFor(nextEntry)
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
			val expected = (requested ?: 0).coerceAtLeast(0)
			check(first in expected - 250..expected + 1500) { "Expected first playback near $expected ms, got $first ms" }
			fun awaitAdvance(from: Long) {
				val deadline = SystemClock.elapsedRealtime() + 10_000
				var position = from
				while (SystemClock.elapsedRealtime() < deadline) {
					onMain {
						check(playbackError == null) { playbackError.orEmpty() }
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
