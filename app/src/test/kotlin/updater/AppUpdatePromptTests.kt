package org.jellyfin.androidtv.updater

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.updater.AppUpdate

class AppUpdatePromptTests : FunSpec({
	test("update popup remains eligible when its dialog takes window focus") {
		val update = testUpdate()

		val openedPrompt = appUpdatePrompt(
			update = update,
			currentPrompt = null,
			isPlayback = false,
			hostWindowFocused = true,
		)
		appUpdatePrompt(
			update = update,
			currentPrompt = openedPrompt,
			isPlayback = false,
			hostWindowFocused = false,
		) shouldBe update
	}

	test("update popup waits while another window owns focus") {
		appUpdatePrompt(
			update = testUpdate(),
			currentPrompt = null,
			isPlayback = false,
			hostWindowFocused = false,
		) shouldBe null
	}

	test("update popup stays hidden during playback") {
		val update = testUpdate()
		appUpdatePrompt(
			update = update,
			currentPrompt = update,
			isPlayback = true,
			hostWindowFocused = false,
		) shouldBe null
	}

	test("update popup stays hidden without an available update") {
		appUpdatePrompt(
			update = null,
			currentPrompt = testUpdate(),
			isPlayback = false,
			hostWindowFocused = true,
		) shouldBe null
	}
})

private fun testUpdate() = AppUpdate(
	versionName = "2026.09.11",
	buildType = "debug",
	releaseName = "Updater test",
	tagName = "v2026.09.11",
	releaseUrl = "https://github.com/thor2002ro/jellyfin-androidtv/releases/tag/v2026.09.11",
	releaseNotes = "Deterministic updater test fixture.",
	prerelease = false,
	publishedAt = "2026-09-11T00:00:00Z",
	assetName = "jellyfin-androidtv-thor-2026.09.11-universal-debug.apk",
	assetUrl = "https://example.invalid/update.apk",
	assetSize = 42_000_000,
	assetDigest = "sha256:${"0".repeat(64)}",
)
