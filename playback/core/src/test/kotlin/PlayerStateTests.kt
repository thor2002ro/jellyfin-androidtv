package org.jellyfin.playback.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
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
	test("playback manager switches its active backend") {
		val initial = backend()
		val replacement = backend()
		val bufferOptions = PlaybackBufferOptions(
			maxBufferDuration = 120.seconds,
			liveTvBufferDuration = 5.seconds,
		)
		val manager = PlaybackManager(
			backend = initial,
			services = mutableListOf(),
			options = PlaybackManagerOptions(
				playerVolumeState = NoOpPlayerVolumeState(),
				defaultRewindAmount = { 10.seconds },
				defaultFastForwardAmount = { 10.seconds },
				bufferOptions = { bufferOptions },
			),
		)

		manager.activeBackends.single() shouldBeSameInstanceAs initial
		manager.isBackendActive(initial) shouldBe true
		manager.isBackendActive(replacement) shouldBe false

		manager.state.setSpeed(1.5f)
		manager.switchBackend(replacement)

		manager.backend shouldBeSameInstanceAs replacement
		manager.activeBackends.single() shouldBeSameInstanceAs replacement
		manager.isBackendActive(initial) shouldBe false
		manager.isBackendActive(replacement) shouldBe true
		verify { initial.setBufferOptions(bufferOptions) }
		verify { initial.onActivated() }
		verify { initial.stop() }
		verify { replacement.setBufferOptions(bufferOptions) }
		verify { replacement.onActivated() }
		verify { replacement.setSpeed(1.5f) }
	}

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

	test("offset adjustment keeps the current subtitle speed") {
		val backend = backend()
		val entry = QueueEntry().apply {
			mediaStream = stream(this, MediaConversionMethod.None)
		}
		val state = playerState(backend, entry, subtitleTimingSupported = true)

		state.setSubtitleTiming(2.seconds, 1.01f)
		state.adjustSubtitleTimingOffset(1.seconds)

		state.subtitleTimingOffset.value shouldBe 3.seconds
		state.subtitleTimingSpeed.value shouldBe 1.01f
		verify { backend.setSubtitleTiming(3.seconds, 1.01f) }
	}

	test("backend without subtitle speed support keeps the default speed") {
		val backend = backend(supportsSubtitleTimingSpeed = false)
		val state = playerState(backend, QueueEntry(), subtitleTimingSupported = true)

		state.setSubtitleTiming(2.seconds, 1.01f)

		state.subtitleTimingOffset.value shouldBe 2.seconds
		state.subtitleTimingSpeed.value shouldBe 1f
		verify { backend.setSubtitleTiming(2.seconds, 1f) }
	}

	test("unsupported subtitle timing ignores adjustments but allows reset") {
		val backend = backend()
		val state = playerState(backend, QueueEntry())

		state.setSubtitleTiming(2.seconds, 1.01f)

		state.subtitleTimingOffset.value shouldBe Duration.ZERO
		state.subtitleTimingSpeed.value shouldBe 1f
		verify(exactly = 0) { backend.setSubtitleTiming(2.seconds, 1.01f) }

		state.resetSubtitleTiming()
		verify { backend.setSubtitleTiming(Duration.ZERO, 1f) }
	}

	test("temporary subtitle timing support loss preserves calibration") {
		val backend = backend()
		val backendService = BackendService()
		val state = playerState(
			backend = backend,
			entry = QueueEntry(),
			subtitleTimingSupported = true,
			backendService = backendService,
		)
		state.setSubtitleTiming(2.seconds, 1.01f)

		backendService.BackendEventListener().onSubtitleTimingOffsetSupportChange(
			supported = false,
			resetTimingOnUnsupported = false,
		)

		state.subtitleTimingOffsetSupported.value shouldBe false
		state.subtitleTimingOffset.value shouldBe 2.seconds
		state.subtitleTimingSpeed.value shouldBe 1.01f

		backendService.BackendEventListener().onSubtitleTimingOffsetSupportChange(
			supported = false,
			resetTimingOnUnsupported = true,
		)
		state.subtitleTimingOffset.value shouldBe Duration.ZERO
		state.subtitleTimingSpeed.value shouldBe 1f
	}
})

private fun playerState(
	backend: PlayerBackend,
	entry: QueueEntry,
	subtitleTimingSupported: Boolean = false,
	backendService: BackendService = BackendService(),
): MutablePlayerState {
	backendService.switchBackend(backend)
	val queue = mockk<QueueService>(relaxed = true) {
		every { this@mockk.entry } returns MutableStateFlow(entry)
	}

	val state = MutablePlayerState(
		options = PlaybackManagerOptions(
			playerVolumeState = NoOpPlayerVolumeState(),
			defaultRewindAmount = { 10.seconds },
			defaultFastForwardAmount = { 10.seconds },
		),
		backendService = backendService,
		queue = queue,
	)
	if (subtitleTimingSupported) {
		backendService.BackendEventListener().onSubtitleTimingOffsetSupportChange(true)
	}
	return state
}

private fun backend(supportsSubtitleTimingSpeed: Boolean = true) = mockk<PlayerBackend>(relaxed = true) {
	every { this@mockk.supportsSubtitleTimingSpeed } returns supportsSubtitleTimingSpeed
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
