package org.jellyfin.androidtv.updater

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.updater.AppUpdate

class AppUpdatePromptTests : FunSpec({
	test("update popup remains eligible when its dialog takes window focus") {
		val update = testUpdate()

		appUpdatePrompt(update, isPlayback = false) shouldBe update
		appUpdatePrompt(update, isPlayback = false) shouldBe update
	}

	test("update popup stays hidden during playback") {
		appUpdatePrompt(testUpdate(), isPlayback = true) shouldBe null
	}

	test("update popup stays hidden without an available update") {
		appUpdatePrompt(null, isPlayback = false) shouldBe null
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
