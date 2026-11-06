package org.jellyfin.androidtv.ui.player.video

import android.view.KeyEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class VideoPlayerControlsTests : FunSpec({
	test("up from the top controls opens the Live TV guide") {
		var guideOpened = false
		var chaptersOpened = false

		val handled = handleVideoPlayerControlsUp(
			isLiveTv = true,
			hasChapters = false,
			onOpenLiveTvGuide = { guideOpened = true },
			onOpenChapters = { chaptersOpened = true },
		)

		handled shouldBe true
		guideOpened shouldBe true
		chaptersOpened shouldBe false
	}

	test("up from the top controls opens chapters for on-demand video") {
		var guideOpened = false
		var chaptersOpened = false

		val handled = handleVideoPlayerControlsUp(
			isLiveTv = false,
			hasChapters = true,
			onOpenLiveTvGuide = { guideOpened = true },
			onOpenChapters = { chaptersOpened = true },
		)

		handled shouldBe true
		guideOpened shouldBe false
		chaptersOpened shouldBe true
	}

	test("up from the top controls is left unhandled without a secondary action") {
		handleVideoPlayerControlsUp(
			isLiveTv = false,
			hasChapters = false,
			onOpenLiveTvGuide = {},
			onOpenChapters = {},
		) shouldBe false
	}

	test("up from the bottom controls returns focus to the top controls") {
		var movedFocus = false

		val handled = handleVideoPlayerBottomControlsUp(
			keyCode = KeyEvent.KEYCODE_DPAD_UP,
			action = KeyEvent.ACTION_DOWN,
			repeatCount = 0,
			onMoveFocusToTop = { movedFocus = true },
		)

		handled shouldBe true
		movedFocus shouldBe true
	}

	test("bottom controls leave other key events unhandled") {
		var movedFocus = false

		handleVideoPlayerBottomControlsUp(
			keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
			action = KeyEvent.ACTION_DOWN,
			repeatCount = 0,
			onMoveFocusToTop = { movedFocus = true },
		) shouldBe false

		handleVideoPlayerBottomControlsUp(
			keyCode = KeyEvent.KEYCODE_DPAD_UP,
			action = KeyEvent.ACTION_UP,
			repeatCount = 0,
			onMoveFocusToTop = { movedFocus = true },
		) shouldBe false

		movedFocus shouldBe false
	}
})
