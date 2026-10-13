package org.jellyfin.androidtv.ui.player.base

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlayerOverlayLayoutTests : FunSpec({
	test("disabled player overlay never claims focus") {
		playerOverlayFocusTarget(
			inputEnabled = false,
			controlsVisible = true,
			windowFocused = true,
			hasControls = true,
			controlsHaveFocus = false,
		) shouldBe PlayerOverlayFocusTarget.NONE
	}

	test("visible enabled player overlay focuses its controls") {
		playerOverlayFocusTarget(
			inputEnabled = true,
			controlsVisible = true,
			windowFocused = true,
			hasControls = true,
			controlsHaveFocus = false,
		) shouldBe PlayerOverlayFocusTarget.CONTROLS
	}

	test("hidden enabled player overlay focuses its root") {
		playerOverlayFocusTarget(
			inputEnabled = true,
			controlsVisible = false,
			windowFocused = true,
			hasControls = true,
			controlsHaveFocus = false,
		) shouldBe PlayerOverlayFocusTarget.PLAYER
	}
})
