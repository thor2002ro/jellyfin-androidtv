package org.jellyfin.playback.jellyfin.recovery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.activate
import org.jellyfin.playback.core.backend.createPlaybackErrorOrigin
import org.jellyfin.playback.core.backend.matches
import org.jellyfin.playback.core.queue.QueueEntry
import kotlin.time.Duration.Companion.seconds

class NetworkPlaybackRecoveryServiceTests : FunSpec({
	test("recovery does not reload resumed or paused playback") {
		hasPlaybackRecovered(PlayState.PLAYING, 2.seconds, 3.seconds) shouldBe true
		hasPlaybackRecovered(PlayState.PAUSED, 2.seconds, 2.seconds) shouldBe true
		hasPlaybackRecovered(PlayState.ERROR, 2.seconds, 2.seconds) shouldBe false
	}

	test("automatic recovery only handles network and libVLC errors") {
		isRecoverablePlaybackError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED") shouldBe true
		isRecoverablePlaybackError("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT") shouldBe true
		isRecoverablePlaybackError("LIBVLC_ERROR") shouldBe true
		isRecoverablePlaybackError("ERROR_CODE_DECODING_FAILED") shouldBe false
		isRecoverablePlaybackError("DOVI_TRANSFORMATION_FAILED_RPU_WRITE_FAILED") shouldBe false
		isRecoverablePlaybackError("DOVI_TRANSFORMATION_FAILED_UNKNOWN") shouldBe false
	}

	test("stalled buffering waits for the configured threshold") {
		shouldRecoverStalledBuffer(consecutiveChecks = 4, requiredChecks = 5, hasPlayed = true) shouldBe false
		shouldRecoverStalledBuffer(consecutiveChecks = 5, requiredChecks = 5, hasPlayed = true) shouldBe true
	}

	test("initial buffering uses a longer recovery threshold") {
		shouldRecoverStalledBuffer(consecutiveChecks = 9, requiredChecks = 5, hasPlayed = false) shouldBe false
		shouldRecoverStalledBuffer(consecutiveChecks = 10, requiredChecks = 5, hasPlayed = false) shouldBe true
	}

	test("active generic recovery is cancelled joined and ownership-cleared") {
		runBlocking {
			val started = CompletableDeferred<Unit>()
			val finished = CompletableDeferred<Unit>()
			val worker = launch(Dispatchers.Default) {
				started.complete(Unit)
				try {
					awaitCancellation()
				} finally {
					finished.complete(Unit)
				}
			}
			started.await()
			cancelAndJoinRecoveryJob(worker, currentJob = null) shouldBe true

			worker.isCancelled shouldBe true
			finished.isCompleted shouldBe true
		}
	}

	test("an old job completion cannot clear newer recovery ownership") {
		val entry = QueueEntry()
		val oldToken = Any()
		val newer = NetworkRecoveryJobOwnership(entry, Any(), Job())

		clearFinishedNetworkRecoveryOwnership(newer, oldToken) shouldBe newer
	}

	test("ordinary recovery rejects a stale resolved stream origin") {
		val entry = QueueEntry()
		val stale = entry.createPlaybackErrorOrigin("source-a").also { it.activate() }
		val current = entry.createPlaybackErrorOrigin("source-b").also { it.activate() }

		isOrdinaryRecoveryErrorEligible(
			PlaybackError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", origin = stale),
			entry,
		) shouldBe false
		isOrdinaryRecoveryErrorEligible(
			PlaybackError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", origin = current),
			entry,
		) shouldBe true
	}

	test("overlapping resolution does not replace origin until the returned stream is activated") {
		val entry = QueueEntry()
		val playing = entry.createPlaybackErrorOrigin("source-a").also { it.activate() }
		val resolving = entry.createPlaybackErrorOrigin("source-b")

		playing.matches(entry) shouldBe true
		resolving.matches(entry) shouldBe false
		resolving.activate()
		playing.matches(entry) shouldBe false
		resolving.matches(entry) shouldBe true
	}
})
