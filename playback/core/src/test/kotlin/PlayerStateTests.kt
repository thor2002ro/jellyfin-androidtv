package org.jellyfin.playback.core

import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.playback.core.backend.BackendService
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamContainer
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.QueueService
import org.jellyfin.playback.core.queue.liveStreamTargetOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PlayerStateTests : FunSpec({
	test("direct play Live TV ignores seek requests") {
		val backend = backend()
		val entry = QueueEntry().apply {
			liveStreamTargetOffset = 3.seconds
			mediaStream = stream(this, MediaConversionMethod.None)
		}
		val state = playerState(backend, entry)

		state.seek(30.seconds)
		state.fastForward()
		state.rewind()

		verify(exactly = 0) { backend.seekTo(any()) }
	}

	test("non Live TV seek requests still pass through") {
		val backend = backend()
		val entry = QueueEntry().apply {
			mediaStream = stream(this, MediaConversionMethod.None)
		}
		val state = playerState(backend, entry)

		state.seek(30.seconds)

		verify { backend.seekTo(30.seconds) }
	}
})

private fun playerState(
	backend: PlayerBackend,
	entry: QueueEntry,
): MutablePlayerState {
	val backendService = BackendService().apply {
		switchBackend(backend)
	}
	val queue = mockk<QueueService>(relaxed = true) {
		every { this@mockk.entry } returns MutableStateFlow(entry)
	}

	return MutablePlayerState(
		options = PlaybackManagerOptions(
			playerVolumeState = NoOpPlayerVolumeState(),
			defaultRewindAmount = { 10.seconds },
			defaultFastForwardAmount = { 10.seconds },
		),
		backendService = backendService,
		queue = queue,
	)
}

private fun backend() = mockk<PlayerBackend>(relaxed = true) {
	every { getPositionInfo() } returns PositionInfo(20.seconds, 20.seconds, 120.seconds)
	justRun { seekTo(any()) }
}

private fun stream(
	entry: QueueEntry,
	conversionMethod: MediaConversionMethod,
) = PlayableMediaStream(
	identifier = "test",
	conversionMethod = conversionMethod,
	container = MediaStreamContainer("ts"),
	tracks = emptyList(),
	queueEntry = entry,
	url = "http://example.test/live.ts",
)
