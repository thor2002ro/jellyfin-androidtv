package org.jellyfin.androidtv.test

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class UpdaterTestScenarioTests : FunSpec({
	test("tester covers every updater result visible to a TV user") {
		UpdaterTestScenario.entries.map(UpdaterTestScenario::label) shouldContainExactly listOf(
			"Stable update",
			"Pre-release update",
			"No update",
			"GitHub error",
			"Downloading",
			"Download failed",
			"Install permission",
			"Installer opened",
			"Popup stability",
		)
	}

	test("popup stability scenario exercises repeated focus changes") {
		UpdaterTestScenario.POPUP_STABILITY.focusTransitions shouldBe 20
	}
})
