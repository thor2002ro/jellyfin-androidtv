package org.jellyfin.playback.jellyfin.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.liveStreamTargetOffset
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.forceTranscodingRecoveryAttempts
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class LiveTvPlaybackRecoveryServiceTests : FunSpec({
	test("buffered Live TV stalls wait before reloading") {
		shouldReloadBufferedLiveTvStall(bufferedRecoveryAttempts = 1, attemptsBeforeReload = 3) shouldBe false
		shouldReloadBufferedLiveTvStall(bufferedRecoveryAttempts = 2, attemptsBeforeReload = 3) shouldBe false
		shouldReloadBufferedLiveTvStall(bufferedRecoveryAttempts = 3, attemptsBeforeReload = 3) shouldBe true
	}

	test("playing Live TV resets recovery attempts") {
		val entry = QueueEntry().apply {
			baseItem = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.TV_CHANNEL)
			liveStreamTargetOffset = 30.seconds
			forceTranscodingRecoveryAttempts = 1
		}

		resetLiveTvRecoveryAttempts(entry, LiveTvPlaybackPolicy())

		entry.liveStreamTargetOffset shouldBe LiveTvPlaybackPolicy.INITIAL_LIVE_STREAM_TARGET_OFFSET
		entry.forceTranscodingRecoveryAttempts shouldBe null
	}

	test("dedicated Dolby Vision recovery cancels every active Live TV recovery job") {
		val playbackError = Job()
		val stalledBuffer = Job()
		val streamEnd = Job()

		runBlocking {
			cancelAndJoinLiveTvRecoveryJobs(listOf(playbackError, stalledBuffer, streamEnd), currentJob = null)
		}

		playbackError.isCancelled shouldBe true
		stalledBuffer.isCancelled shouldBe true
		streamEnd.isCancelled shouldBe true
	}
})
