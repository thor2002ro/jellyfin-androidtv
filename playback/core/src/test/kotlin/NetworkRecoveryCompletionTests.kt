package org.jellyfin.playback.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.activate
import org.jellyfin.playback.core.backend.createPlaybackErrorOrigin
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamContainer
import org.jellyfin.playback.core.mediastream.MediaStreamResolver
import org.jellyfin.playback.core.mediastream.MediaStreamService
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.startPosition
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.QueueEntryMetadata
import org.jellyfin.playback.core.queue.QueueService
import org.jellyfin.playback.core.queue.metadata
import org.jellyfin.playback.core.queue.supplier.QueueSupplier
import org.jellyfin.playback.jellyfin.livetv.LiveTvPlaybackPolicy
import org.jellyfin.playback.jellyfin.recovery.NetworkPlaybackRecoveryService
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkRecoveryCompletionTests : FunSpec({
	val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
	beforeSpec {
		Dispatchers.setMain(dispatcher)
	}
	afterSpec {
		Dispatchers.resetMain()
		dispatcher.close()
	}

	test("native errors before and after PLAYING preserve the episode and original errors") {
		withContext(dispatcher) {
			val fixture = RecoveryCompletionFixture()
			try {
				fixture.startRecovery()
				withTimeout(10.seconds) { fixture.reloaded.await() }
				val replacement = requireNotNull(fixture.first.mediaStream)
				val errors = listOf("MPV_ERROR", "MPV_HTTP_ERROR")
				for ((index, codeName) in errors.withIndex()) {
					if (index > 0) fixture.events.onPlayStateChange(PlayState.PLAYING)
					fixture.events.onPlaybackError(PlaybackError(codeName, origin = replacement.errorOrigin))
					fixture.events.onPlayStateChange(PlayState.ERROR)
					fixture.events.onPlayStateChange(PlayState.STOPPED)
					fixture.events.onMediaStreamEnd(replacement)
					fixture.events.onMediaStreamEnd(replacement)
					fixture.completed shouldBe emptyList()
					fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
					fixture.errors.map { it.codeName } shouldBe errors.take(index + 1)
					fixture.recovery.recovering.value shouldBe true
					fixture.replacementCount shouldBe 1
					fixture.resolutionCount.get() shouldBe 1
				}

				fixture.events.onPlayStateChange(PlayState.PLAYING)
				fixture.position = PositionInfo(99.5.seconds, 100.seconds, 100.seconds)
				fixture.events.onPlayStateChange(PlayState.STOPPED)
				fixture.events.onMediaStreamEnd(replacement)
				withTimeout(2.seconds) { fixture.queue.entry.first { it === fixture.second } }
				fixture.completed shouldBe listOf(replacement)
			} finally {
				fixture.job.cancel()
			}
		}
	}

	test("ordinary recovery does not swallow a live TV end after changing entries") {
		withContext(dispatcher) {
			val fixture = RecoveryCompletionFixture()
			try {
				fixture.startAndDisconnect()
				fixture.second.baseItem = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.TV_CHANNEL)
				fixture.queue.setIndex(1)
				val channel = requireNotNull(fixture.second.mediaStream)
				fixture.events.onPlayStateChange(PlayState.PLAYING)
				fixture.events.onPlaybackError(PlaybackError("MPV_HTTP_ERROR", origin = channel.errorOrigin))
				fixture.events.onPlayStateChange(PlayState.ERROR)
				fixture.events.onPlayStateChange(PlayState.STOPPED)
				fixture.events.onMediaStreamEnd(channel)
				fixture.completed shouldBe listOf(channel)
			} finally {
				fixture.job.cancel()
			}
		}
	}
	for (errorRetry in listOf(false, true)) {
		test("network retry survives stopped and EOF with an active error retry = $errorRetry") {
			withContext(dispatcher) {
				val fixture = RecoveryCompletionFixture()
				try {
					fixture.startAndDisconnect()
					if (errorRetry) {
						fixture.position = fixture.position.copy(duration = Duration.ZERO)
						fixture.events.onPlaybackError(PlaybackError("MPV_HTTP_ERROR", origin = fixture.original.errorOrigin))
					}
					fixture.events.onPlayStateChange(PlayState.STOPPED)
					fixture.events.onMediaStreamEnd(fixture.original)
					fixture.completed shouldBe emptyList()
					fixture.queue.entry.value shouldBeSameInstanceAs fixture.first

					fixture.online.set(true)
					withTimeout(10.seconds) { fixture.reloaded.await() } shouldBe 20.seconds
					fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
					fixture.manager.state.playState.value shouldBe PlayState.BUFFERING
					fixture.recovery.recovering.value shouldBe true
					fixture.events.onPlayStateChange(PlayState.PLAYING)
					withTimeout(2.seconds) { fixture.recovery.recovering.first { !it } }

					fixture.events.onPlayStateChange(PlayState.STOPPED)
					fixture.events.onMediaStreamEnd(requireNotNull(fixture.first.mediaStream))
					withTimeout(2.seconds) { fixture.queue.entry.first { it === fixture.second } }
					fixture.completed.size shouldBe 1
				} finally {
					fixture.job.cancel()
				}
			}
		}
	}

	for (paused in listOf(false, true)) {
		test("replacement EOF before playback resumes stays on the episode; paused = $paused") {
			withContext(dispatcher) {
				val fixture = RecoveryCompletionFixture()
				try {
					fixture.startRecovery()
					withTimeout(10.seconds) { fixture.reloaded.await() }
					val replacement = requireNotNull(fixture.first.mediaStream)
					if (paused) {
						fixture.events.onPlayStateChange(PlayState.PAUSED)
						withTimeout(2.seconds) { fixture.recovery.recovering.first { !it } }
					}
					fixture.events.onPlayStateChange(PlayState.STOPPED)
					fixture.events.onMediaStreamEnd(replacement)
					withTimeout(2.seconds) { fixture.manager.state.playState.first { it == PlayState.ERROR } }
					fixture.recovery.recovering.value shouldBe !paused
					fixture.events.onMediaStreamEnd(replacement)
					fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
					fixture.completed shouldBe emptyList()
					fixture.errors.single().codeName shouldBe "MEDIA_STREAM_RECOVERY_FAILED"
					fixture.events.onPlayStateChange(PlayState.PLAYING)
					fixture.position = PositionInfo(99.5.seconds, 100.seconds, 100.seconds)
					fixture.events.onPlayStateChange(PlayState.STOPPED)
					fixture.events.onMediaStreamEnd(replacement)
					withTimeout(2.seconds) { fixture.queue.entry.first { it === fixture.second } }
					fixture.completed shouldBe listOf(replacement)
				} finally {
					fixture.job.cancel()
				}
			}
		}
	}

	test("Jellyfin resolution failures keep retrying the same position until playback recovers") {
		withContext(dispatcher) {
			val calls = AtomicInteger()
			val positions = mutableListOf<Duration?>()
			val resolver = object : MediaStreamResolver {
				override suspend fun getStream(queueEntry: QueueEntry, startPosition: Duration?): PlayableMediaStream {
					positions += startPosition
					if (calls.incrementAndGet() <= 3) throw SocketTimeoutException("Jellyfin unavailable")
					return stream(queueEntry, "recovered")
				}
			}
			val fixture = RecoveryCompletionFixture(resolver)
			try {
				fixture.startRecovery()
				withTimeout(10.seconds) { fixture.manager.state.playState.first { it == PlayState.ERROR } }
				fixture.recovery.recovering.value shouldBe true
				fixture.events.onMediaStreamEnd(fixture.original)
				fixture.events.onMediaStreamEnd(fixture.original)
				delay(2.seconds)
				calls.get() shouldBe 1
				withTimeout(12.seconds) { fixture.reloaded.await() } shouldBe 20.seconds
				fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
				fixture.completed shouldBe emptyList()
				fixture.errors.single().codeName shouldBe "MEDIA_STREAM_RECOVERY_FAILED"
				calls.get() shouldBe 4
				positions shouldBe listOf(20.seconds, 20.seconds, 20.seconds, 20.seconds)
				fixture.replacementCount shouldBe 1
				fixture.events.onPlayStateChange(PlayState.PLAYING)
				withTimeout(2.seconds) { fixture.recovery.recovering.first { !it } }
			} finally {
				fixture.job.cancel()
			}
		}
	}

	test("a newer Jellyfin request is not failed by an older request timing out") {
		withContext(dispatcher) {
			val started = List(2) { CompletableDeferred<Unit>() }
			val responses = List(2) { CompletableDeferred<PlayableMediaStream>() }
			val calls = AtomicInteger()
			val resolver = object : MediaStreamResolver {
				override suspend fun getStream(queueEntry: QueueEntry, startPosition: Duration?): PlayableMediaStream {
					val index = calls.getAndIncrement()
					started[index].complete(Unit)
					return responses[index].await()
				}
			}
			val fixture = RecoveryCompletionFixture(resolver)
			try {
				fixture.startRecovery()
				withTimeout(10.seconds) { started[0].await() }
				val newer = async { fixture.manager.reloadCurrentMediaStream(30.seconds) }
				withTimeout(2.seconds) { started[1].await() }
				responses[0].completeExceptionally(SocketTimeoutException("old request"))
				withTimeout(2.seconds) { fixture.recovery.recovering.first { !it } }
				fixture.errors shouldBe emptyList()
				fixture.manager.state.playState.value shouldBe PlayState.STOPPED
				newer.isCompleted shouldBe false
				responses[1].complete(stream(fixture.first, "newer"))
				withTimeout(2.seconds) { newer.await() } shouldBe true
				fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
				fixture.first.mediaStream?.identifier shouldBe "newer"
				calls.get() shouldBe 2
				fixture.replacementCount shouldBe 1
			} finally {
				fixture.job.cancel()
			}
		}
	}

	test("continuous recovery does not overtake a newer user request during replacement startup") {
		withContext(dispatcher) {
			val calls = AtomicInteger()
			val newerStarted = CompletableDeferred<Unit>()
			val newerResponse = CompletableDeferred<PlayableMediaStream>()
			val resolver = object : MediaStreamResolver {
				override suspend fun getStream(queueEntry: QueueEntry, startPosition: Duration?): PlayableMediaStream {
					return when (calls.incrementAndGet()) {
						1 -> stream(queueEntry, "automatic")
						2 -> { newerStarted.complete(Unit); newerResponse.await() }
						else -> error("Automatic recovery overtook user request")
					}
				}
			}
			val fixture = RecoveryCompletionFixture(resolver)
			try {
				fixture.startRecovery()
				withTimeout(10.seconds) { fixture.reloaded.await() }
				val newer = async { fixture.manager.reloadCurrentMediaStream(30.seconds) }
				withTimeout(2.seconds) { newerStarted.await() }
				fixture.events.onPlaybackError(PlaybackError("MPV_HTTP_ERROR", origin = fixture.first.mediaStream?.errorOrigin))
				fixture.events.onPlayStateChange(PlayState.ERROR)
				withTimeout(2.seconds) { fixture.recovery.recovering.first { !it } }
				delay(4.seconds)
				calls.get() shouldBe 2
				newer.isCompleted shouldBe false
				newerResponse.complete(stream(fixture.first, "user"))
				withTimeout(2.seconds) { newer.await() } shouldBe true
				delay(4.seconds)
				calls.get() shouldBe 2
				fixture.first.mediaStream?.identifier shouldBe "user"
				fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
				fixture.completed shouldBe emptyList()
			} finally {
				fixture.job.cancel()
			}
		}
	}

	test("stop during replacement startup cancels recovery and keeps playback stopped") {
		withContext(dispatcher) {
			val fixture = RecoveryCompletionFixture()
			try {
				fixture.startRecovery()
				withTimeout(10.seconds) { fixture.reloaded.await() }
				fixture.manager.state.stop()
				withTimeout(2.seconds) { fixture.recovery.recovering.first { !it } }
				fixture.queue.entry.value shouldBe null
				fixture.manager.state.playState.value shouldBe PlayState.STOPPED
				fixture.errors shouldBe emptyList()
			} finally {
				fixture.job.cancel()
			}
		}
	}

	test("a replacement that never starts is retried after its startup timeout") {
		withContext(dispatcher) {
			val fixture = RecoveryCompletionFixture()
			try {
				fixture.startRecovery()
				withTimeout(10.seconds) { fixture.reloaded.await() }
				withTimeout(35.seconds) { fixture.manager.state.playState.first { it == PlayState.ERROR } }
				fixture.recovery.recovering.value shouldBe true
				fixture.events.onPlayStateChange(PlayState.BUFFERING)
				fixture.position = fixture.position.copy(buffer = 21.seconds)
				delay(4.seconds)
				fixture.recovery.recovering.value shouldBe true
				fixture.replacementCount shouldBe 1
				withTimeout(5.seconds) { fixture.replacementPositions.first { it.size == 2 } }
				fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
				fixture.completed shouldBe emptyList()
				fixture.replacementCount shouldBe 2
				fixture.resolutionCount.get() shouldBe 2
				fixture.errors.single().codeName shouldBe "MEDIA_STREAM_RECOVERY_FAILED"
				fixture.events.onPlayStateChange(PlayState.PLAYING)
				withTimeout(2.seconds) { fixture.recovery.recovering.first { !it } }
			} finally {
				fixture.job.cancel()
			}
		}
	}

	for ((pause, manualReplacement) in listOf(false to false, true to false, true to true)) {
		test("a failed native reload retains its saved position after timestamp reset; pause = $pause, manual = $manualReplacement") {
			withContext(dispatcher) {
				val fixture = RecoveryCompletionFixture()
				try {
					fixture.startRecovery()
					withTimeout(10.seconds) { fixture.reloaded.await() }
					if (pause) fixture.events.onPlayStateChange(PlayState.PLAYING)
					if (manualReplacement) {
						fixture.first.mediaStream = stream(fixture.first, "manual").also { it.errorOrigin?.activate() }
						fixture.position = PositionInfo(30.seconds, 30.seconds, 100.seconds)
					} else fixture.position = PositionInfo.EMPTY
					fixture.events.onPlaybackError(PlaybackError("MPV_HTTP_ERROR", origin = fixture.first.mediaStream?.errorOrigin))
					fixture.events.onPlayStateChange(PlayState.ERROR)
					if (pause) {
						fixture.manager.state.pause()
						withTimeout(2.seconds) { fixture.recovery.recovering.first { !it } }
						fixture.manager.state.unpause()
						fixture.events.onPlayStateChange(PlayState.BUFFERING)
					}
					withTimeout(5.seconds) { fixture.replacementPositions.first { it.size == 2 } } shouldBe
						listOf(20.seconds, if (manualReplacement) 30.seconds else 20.seconds)
					fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
					fixture.completed shouldBe emptyList()
					fixture.events.onPlayStateChange(PlayState.PLAYING)
					withTimeout(2.seconds) { fixture.recovery.recovering.first { !it } }
				} finally {
					fixture.job.cancel()
				}
			}
		}
	}

	for (pause in listOf(false, true)) {
		test("stop or pause during a Jellyfin request cancels its late response; pause = $pause") {
			withContext(dispatcher) {
				val started = CompletableDeferred<Unit>()
				val response = CompletableDeferred<PlayableMediaStream>()
				val calls = AtomicInteger()
				val resolver = object : MediaStreamResolver {
					override suspend fun getStream(queueEntry: QueueEntry, startPosition: Duration?): PlayableMediaStream {
						calls.incrementAndGet()
						started.complete(Unit)
						return response.await()
					}
				}
				val fixture = RecoveryCompletionFixture(resolver)
				try {
					fixture.startRecovery()
					withTimeout(10.seconds) { started.await() }
					repeat(3) { fixture.events.onPlaybackError(PlaybackError("MPV_HTTP_ERROR", origin = fixture.original.errorOrigin)) }
					fixture.events.onPlayStateChange(PlayState.ERROR)
					delay(4.seconds)
					calls.get() shouldBe 1
					if (pause) fixture.manager.state.pause() else fixture.manager.state.stop()
					withTimeout(2.seconds) { fixture.recovery.recovering.first { !it } }
					response.complete(stream(fixture.first, "late"))
					delay(100L)
					fixture.replacementCount shouldBe 0
					fixture.first.mediaStream shouldBeSameInstanceAs fixture.original
					if (pause) {
						fixture.events.onPlaybackError(PlaybackError("MPV_HTTP_ERROR", origin = fixture.original.errorOrigin))
						fixture.events.onPlayStateChange(PlayState.ERROR)
						delay(4.seconds)
						calls.get() shouldBe 1
						fixture.recovery.recovering.value shouldBe false
						fixture.events.onPlayStateChange(PlayState.PAUSED)
					}
					fixture.manager.state.playState.value shouldBe if (pause) PlayState.PAUSED else PlayState.STOPPED
					fixture.errors.map { it.codeName } shouldBe List(if (pause) 4 else 3) { "MPV_HTTP_ERROR" }
					if (pause) {
						fixture.manager.state.unpause()
						fixture.events.onPlayStateChange(PlayState.BUFFERING)
						withTimeout(5.seconds) { fixture.reloaded.await() }
						calls.get() shouldBe 2
					}
				} finally {
					fixture.job.cancel()
				}
			}
		}
	}

	test("stopping and selecting the same queue entry does not revive its old retry worker") {
		withContext(dispatcher) {
			val calls = AtomicInteger()
			val resolver = object : MediaStreamResolver {
				override suspend fun getStream(queueEntry: QueueEntry, startPosition: Duration?): PlayableMediaStream {
					calls.incrementAndGet()
					throw SocketTimeoutException("offline")
				}
			}
			val fixture = RecoveryCompletionFixture(resolver)
			try {
				fixture.startRecovery()
				withTimeout(10.seconds) { fixture.manager.state.playState.first { it == PlayState.ERROR } }
				fixture.recovery.recovering.value shouldBe true
				fixture.manager.state.stop()
				fixture.queue.addSupplier(object : QueueSupplier {
					override val size = 1
					override suspend fun getItem(index: Int) = fixture.first.takeIf { index == 0 }
				})
				fixture.queue.setIndex(0)
				fixture.events.onPlayStateChange(PlayState.BUFFERING)
				delay(4.seconds)
				calls.get() shouldBe 1
				fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
				fixture.manager.state.playState.value shouldBe PlayState.BUFFERING
				fixture.completed shouldBe emptyList()
			} finally {
				fixture.job.cancel()
			}
		}
	}

	for (pause in listOf(false, true)) {
		test("a nonrecoverable decoder error stops timed network retries; pause = $pause") {
			withContext(dispatcher) {
				val fixture = RecoveryCompletionFixture()
				try {
					fixture.startRecovery()
					withTimeout(10.seconds) { fixture.reloaded.await() }
					if (pause) fixture.manager.state.pause()
					fixture.events.onPlaybackError(PlaybackError("ERROR_CODE_DECODING_FAILED", origin = fixture.first.mediaStream?.errorOrigin))
					fixture.events.onPlayStateChange(PlayState.ERROR)
					withTimeout(2.seconds) { fixture.recovery.recovering.first { !it } }
					if (pause) {
						fixture.manager.state.unpause()
						fixture.events.onPlayStateChange(PlayState.BUFFERING)
					}
					delay(4.seconds)
					fixture.replacementCount shouldBe 1
					fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
					fixture.errors.map { it.codeName } shouldBe listOf("ERROR_CODE_DECODING_FAILED")
				} finally {
					fixture.job.cancel()
				}
			}
		}
	}

	test("native recovery clears the old position before a healthy seek and a later failure") {
		withContext(dispatcher) {
			val fixture = RecoveryCompletionFixture()
			try {
				fixture.startAndDisconnect()
				fixture.online.set(true)
				delay(4.seconds)
				fixture.position = PositionInfo(25.seconds, 25.seconds, 100.seconds)
				withTimeout(5.seconds) { fixture.recovery.recovering.first { !it } }
				val backend = fixture.manager.backend
				every { backend.seekTo(60.seconds) } answers {
					fixture.position = PositionInfo(60.seconds, 60.seconds, 100.seconds)
					fixture.events.onPlayStateChange(PlayState.BUFFERING)
					true
				}
				fixture.manager.state.seek(60.seconds)
				delay(4.seconds)
				fixture.resolutionCount.get() shouldBe 0
				fixture.events.onPlaybackError(PlaybackError("MPV_HTTP_ERROR", origin = fixture.original.errorOrigin))
				fixture.events.onPlayStateChange(PlayState.ERROR)
				withTimeout(5.seconds) { fixture.reloaded.await() } shouldBe 60.seconds
				fixture.resolutionCount.get() shouldBe 1
				fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
			} finally { fixture.job.cancel() }
		}
	}

	test("native progress after pause releases the interrupted unknown-duration stream") {
		withContext(dispatcher) {
			val fixture = RecoveryCompletionFixture()
			try {
				fixture.position = PositionInfo(20.seconds, 20.seconds, Duration.ZERO)
				fixture.startAndDisconnect()
				fixture.manager.state.pause()
				fixture.manager.state.unpause()
				fixture.online.set(true)
				delay(1.seconds)
				fixture.position = PositionInfo(25.seconds, 25.seconds, Duration.ZERO)
				withTimeout(5.seconds) { fixture.recovery.recovering.first { !it } }
				fixture.resolutionCount.get() shouldBe 0
				fixture.events.onPlayStateChange(PlayState.STOPPED)
				fixture.events.onMediaStreamEnd(fixture.original)
				withTimeout(2.seconds) { fixture.queue.entry.first { it === fixture.second } }
				fixture.completed shouldBe listOf(fixture.original)
			} finally { fixture.job.cancel() }
		}
	}

	for ((accepted, paused) in listOf(false to false, true to false, true to true)) {
		test("recovery respects the latest accepted seek; accepted = $accepted, paused = $paused") {
			withContext(dispatcher) {
				val fixture = RecoveryCompletionFixture()
				try {
					fixture.startRecovery()
					withTimeout(10.seconds) { fixture.reloaded.await() }
					if (paused) {
						fixture.manager.state.pause()
						fixture.events.onPlayStateChange(PlayState.PAUSED)
					}
					val backend = fixture.manager.backend
					every { backend.seekTo(60.seconds) } answers {
						if (accepted) fixture.position = PositionInfo(60.seconds, 60.seconds, 100.seconds)
						accepted
					}
					fixture.manager.state.seek(60.seconds)
					fixture.events.onPlaybackError(PlaybackError("MPV_HTTP_ERROR", origin = fixture.first.mediaStream?.errorOrigin))
					fixture.events.onPlayStateChange(PlayState.ERROR)
					if (paused) {
						delay(4.seconds)
						fixture.resolutionCount.get() shouldBe 1
						fixture.recovery.recovering.value shouldBe false
						fixture.manager.state.unpause()
						fixture.events.onPlayStateChange(PlayState.BUFFERING)
					}
					withTimeout(5.seconds) { fixture.replacementPositions.first { it.size == 2 } } shouldBe
						listOf(20.seconds, if (accepted) 60.seconds else 20.seconds)
					fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
				} finally { fixture.job.cancel() }
			}
		}
	}

	test("seeks supersede a pending Jellyfin recovery request without duplicate replacements") {
		withContext(dispatcher) {
			val started = CompletableDeferred<Unit>()
			val cleaningUp = CompletableDeferred<Unit>()
			val releaseCleanup = CompletableDeferred<Unit>()
			val response = CompletableDeferred<PlayableMediaStream>()
			val calls = AtomicInteger()
			val resolver = object : MediaStreamResolver {
				override suspend fun getStream(queueEntry: QueueEntry, startPosition: Duration?): PlayableMediaStream {
					if (calls.incrementAndGet() == 1) {
						started.complete(Unit)
						try {
							return response.await()
						} finally {
							withContext(NonCancellable) {
								cleaningUp.complete(Unit)
								releaseCleanup.await()
							}
						}
					}
					return stream(queueEntry, "latest-seek")
				}
			}
			val fixture = RecoveryCompletionFixture(resolver)
			try {
				fixture.startRecovery()
				withTimeout(10.seconds) { started.await() }
				val backend = fixture.manager.backend
				every { backend.seekTo(40.seconds) } answers {
					fixture.position = PositionInfo(40.seconds, 40.seconds, 100.seconds)
					true
				}
				every { backend.seekTo(60.seconds) } answers {
					fixture.position = PositionInfo(60.seconds, 60.seconds, 100.seconds)
					true
				}
				fixture.manager.state.seek(40.seconds)
				withTimeout(2.seconds) { cleaningUp.await() }
				fixture.manager.state.seek(60.seconds)
				delay(4.seconds)
				calls.get() shouldBe 1
				releaseCleanup.complete(Unit)
				response.complete(stream(fixture.first, "outdated-position"))
				withTimeout(10.seconds) { fixture.reloaded.await() } shouldBe 60.seconds
				fixture.replacementCount shouldBe 1
				calls.get() shouldBe 2
				fixture.first.mediaStream?.identifier shouldBe "latest-seek"
				fixture.errors shouldBe emptyList()
			} finally {
				releaseCleanup.complete(Unit)
				fixture.job.cancel()
			}
		}
	}

	test("pause during an outage keeps the interrupted episode selected on late EOF") {
		withContext(dispatcher) {
			val fixture = RecoveryCompletionFixture()
			try {
				fixture.startAndDisconnect()
				fixture.manager.state.pause()
				fixture.recovery.recovering.value shouldBe false
				fixture.events.onPlayStateChange(PlayState.STOPPED)
				fixture.events.onMediaStreamEnd(fixture.original)
				delay(200L)
				fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
				fixture.completed shouldBe emptyList()
				fixture.events.onPlayStateChange(PlayState.PAUSED)
				fixture.online.set(true)
				delay(4.seconds)
				fixture.resolutionCount.get() shouldBe 0
				fixture.replacementCount shouldBe 0
				fixture.manager.state.playState.value shouldBe PlayState.PAUSED
				fixture.manager.state.unpause()
				fixture.events.onPlayStateChange(PlayState.BUFFERING)
				withTimeout(10.seconds) { fixture.reloaded.await() } shouldBe 20.seconds
				fixture.events.onPlayStateChange(PlayState.PLAYING)
				fixture.position = PositionInfo(99.5.seconds, 100.seconds, 100.seconds)
				fixture.events.onPlayStateChange(PlayState.STOPPED)
				fixture.events.onMediaStreamEnd(requireNotNull(fixture.first.mediaStream))
				withTimeout(2.seconds) { fixture.queue.entry.first { it === fixture.second } }
			} finally { fixture.job.cancel() }
		}
	}

	for (newStream in listOf(false, true)) {
		test("fatal decoder failure blocks reconnect recovery until explicit retry or a new stream; new stream = $newStream") {
			withContext(dispatcher) {
				val fixture = RecoveryCompletionFixture()
				try {
					fixture.startAndDisconnect()
					fixture.events.onPlaybackError(PlaybackError("ERROR_CODE_DECODING_FAILED", origin = fixture.original.errorOrigin))
					fixture.events.onPlayStateChange(PlayState.ERROR)
					fixture.recovery.recovering.value shouldBe false
					fixture.online.set(true)
					delay(7.seconds)
					fixture.resolutionCount.get() shouldBe 0
					fixture.replacementCount shouldBe 0
					fixture.manager.state.playState.value shouldBe PlayState.ERROR
					if (newStream) fixture.first.mediaStream = stream(fixture.first, "manual").also { it.errorOrigin?.activate() }
					else fixture.manager.state.play()
					fixture.events.onPlaybackError(PlaybackError("MPV_HTTP_ERROR", origin = fixture.first.mediaStream?.errorOrigin))
					fixture.events.onPlayStateChange(PlayState.ERROR)
					withTimeout(5.seconds) { fixture.reloaded.await() } shouldBe 20.seconds
					fixture.resolutionCount.get() shouldBe 1
					fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
				} finally { fixture.job.cancel() }
			}
		}
	}

	test("stopping during network retry does not restart the episode") {
		withContext(dispatcher) {
			val fixture = RecoveryCompletionFixture()
			try {
				fixture.startAndDisconnect()
				fixture.manager.state.stop()
				fixture.online.set(true)
				delay(4.seconds)
				fixture.queue.entry.value shouldBe null
				fixture.reloaded.isCompleted shouldBe false
			} finally {
				fixture.job.cancel()
			}
		}
	}

	for ((duration, metadataDuration) in listOf(100.seconds to null, Duration.ZERO to 100.seconds, Duration.ZERO to null)) {
		test("natural completion during network loss advances with duration $duration and metadata $metadataDuration") {
			withContext(dispatcher) {
				val fixture = RecoveryCompletionFixture()
				try {
					fixture.startAndDisconnect()
					fixture.first.metadata = QueueEntryMetadata(duration = metadataDuration)
					fixture.position = PositionInfo(99.5.seconds, 100.seconds, duration)
					fixture.events.onPlayStateChange(PlayState.STOPPED)
					fixture.events.onMediaStreamEnd(fixture.original)
					withTimeout(2.seconds) { fixture.queue.entry.first { it === fixture.second } }
					fixture.completed shouldBe listOf(fixture.original)
				} finally {
					fixture.job.cancel()
				}
			}
		}
	}

	test("Jellyfin duration keeps an interrupted stream selected when the backend duration is unknown") {
		withContext(dispatcher) {
			val fixture = RecoveryCompletionFixture()
			try {
				fixture.startAndDisconnect()
				fixture.first.metadata = QueueEntryMetadata(duration = 100.seconds)
				fixture.position = PositionInfo(20.seconds, 20.seconds, Duration.ZERO)
				fixture.events.onPlayStateChange(PlayState.STOPPED)
				fixture.events.onMediaStreamEnd(fixture.original)
				fixture.completed shouldBe emptyList()
				fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
			} finally {
				fixture.job.cancel()
			}
		}
	}
})

private class RecoveryCompletionFixture(resolverOverride: MediaStreamResolver? = null) {
	val job = Job()
	val online = AtomicBoolean(true)
	val first = QueueEntry()
	val second = QueueEntry()
	val original = stream(first, "original").copy(errorOrigin = first.createPlaybackErrorOrigin("original"))
	val queue = QueueService()
	val recovery = NetworkPlaybackRecoveryService(LiveTvPlaybackPolicy(), online::get)
	val reloaded = CompletableDeferred<Duration?>()
	val completed = mutableListOf<PlayableMediaStream>()
	val errors = mutableListOf<PlaybackError>()
	var replacementCount = 0
	val replacementPositions = MutableStateFlow<List<Duration?>>(emptyList())
	val resolutionCount = AtomicInteger()
	var position = PositionInfo(20.seconds, 20.seconds, 100.seconds)
	private val started = CompletableDeferred<Unit>()
	private val eventSlot = slot<PlayerBackendEventListener>()
	val events get() = eventSlot.captured
	private val backend = mockk<PlayerBackend>(relaxed = true) {
		every { setListener(capture(eventSlot)) } returns Unit
		every { getPositionInfo() } answers { position }
		every { playItem(first) } answers {
			original.errorOrigin?.activate()
			started.complete(Unit)
		}
		every { replaceItem(first) } answers {
			replacementCount++
			replacementPositions.value += first.startPosition
			first.mediaStream?.errorOrigin?.activate()
			events.onPlayStateChange(PlayState.BUFFERING)
			reloaded.complete(first.startPosition)
		}
	}
	private val resolver = resolverOverride ?: mockk<MediaStreamResolver> {
		coEvery { getStream(any(), any()) } answers {
			resolutionCount.incrementAndGet()
			stream(firstArg(), "replacement")
		}
	}
	val manager = PlaybackManager(
		backend = backend,
		services = mutableListOf(queue, MediaStreamService(listOf(resolver), Duration.ZERO), recovery),
		options = PlaybackManagerOptions(mockk(relaxed = true), { 10.seconds }, { 10.seconds }),
		parentJob = job,
	)

	init {
		first.mediaStream = original
		second.mediaStream = stream(second, "next")
		manager.addBackendEventListener(object : PlayerBackendEventListener() {
			override fun onMediaStreamEnd(mediaStream: PlayableMediaStream) { completed += mediaStream }
			override fun onPlaybackError(error: PlaybackError) { errors += error }
		})
	}

	suspend fun startRecovery() {
		startAndDisconnect()
		events.onPlayStateChange(PlayState.STOPPED)
		events.onMediaStreamEnd(original)
		online.set(true)
	}

	suspend fun startAndDisconnect() {
		queue.addSupplier(object : QueueSupplier {
			override val size = 2
			override suspend fun getItem(index: Int) = listOf(first, second).getOrNull(index)
		})
		withTimeout(2.seconds) { started.await() }
		events.onPlayStateChange(PlayState.PLAYING)
		online.set(false)
		withTimeout(5.seconds) { recovery.recovering.first { it } }
	}
}

private fun stream(entry: QueueEntry, identifier: String) = PlayableMediaStream(
	identifier = identifier,
	conversionMethod = MediaConversionMethod.None,
	container = MediaStreamContainer("mkv"),
	tracks = emptyList(),
	queueEntry = entry,
	url = "https://example.test/$identifier.mkv",
	errorOrigin = entry.createPlaybackErrorOrigin(identifier),
)
