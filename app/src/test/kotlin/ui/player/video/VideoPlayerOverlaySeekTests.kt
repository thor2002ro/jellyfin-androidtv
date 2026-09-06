package org.jellyfin.androidtv.ui.player.video

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.core.queue.QueueEntry

class VideoPlayerOverlaySeekTests : FunSpec({
	test("rapid seeking accelerates gradually and caps at three times") {
		(1..6).map(::seekAccelerationMultiplier) shouldBe listOf(1, 1, 2, 3, 3, 3)
	}

	test("pending seek belongs only to its original queue entry and backend") {
		val originalEntry = QueueEntry()
		val originalBackend = mockk<PlayerBackend>()

		isPendingSeekCurrent(originalEntry, originalBackend, originalEntry, originalBackend) shouldBe true
		isPendingSeekCurrent(originalEntry, originalBackend, QueueEntry(), originalBackend) shouldBe false
		isPendingSeekCurrent(originalEntry, originalBackend, originalEntry, mockk()) shouldBe false
		isPendingSeekCurrent(null, originalBackend, null, originalBackend) shouldBe false
	}
})
