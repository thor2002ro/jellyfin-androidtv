package org.jellyfin.androidtv.ui.base

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SeekbarTests : FunSpec({
	test("held seeking ignores hardware repeats until the seek interval passes") {
		shouldHandleHeldSeek(repeatCount = 0, eventTime = 1_000L, lastHandledAt = 0L) shouldBe true
		shouldHandleHeldSeek(repeatCount = 1, eventTime = 1_100L, lastHandledAt = 1_000L) shouldBe false
		shouldHandleHeldSeek(repeatCount = 2, eventTime = 1_250L, lastHandledAt = 1_000L) shouldBe true
	}

	test("scrubbing starts only when a directional key changes progress") {
		shouldStartSeekbarScrubbing(
			isScrubbingKey = true,
			isKeyDown = true,
			progressChanged = false,
			hasScrubCallbacks = true,
		) shouldBe false

		shouldStartSeekbarScrubbing(
			isScrubbingKey = true,
			isKeyDown = true,
			progressChanged = true,
			hasScrubCallbacks = true,
		) shouldBe true
	}

	test("ending active scrubbing emits cleanup callbacks") {
		val scrubbingChanges = mutableListOf<Boolean>()
		val previewChanges = mutableListOf<Float?>()

		endSeekbarScrubbing(
			active = true,
			onScrubbing = scrubbingChanges::add,
			onPreviewSeek = previewChanges::add,
		) shouldBe true

		scrubbingChanges shouldBe listOf(false)
		previewChanges shouldBe listOf(null)
	}

	test("ending inactive scrubbing emits nothing") {
		val scrubbingChanges = mutableListOf<Boolean>()

		endSeekbarScrubbing(
			active = false,
			onScrubbing = scrubbingChanges::add,
			onPreviewSeek = null,
		) shouldBe false

		scrubbingChanges shouldBe emptyList()
	}

	test("disabling an active seekbar finishes scrubbing") {
		shouldFinishSeekbarScrubbing(active = true, focused = true, enabled = false) shouldBe true
		shouldFinishSeekbarScrubbing(active = true, focused = true, enabled = true) shouldBe false
		shouldFinishSeekbarScrubbing(active = false, focused = true, enabled = false) shouldBe false
	}
})
