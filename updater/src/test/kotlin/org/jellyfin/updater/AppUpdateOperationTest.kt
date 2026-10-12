package org.jellyfin.updater

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.io.path.createTempFile

class AppUpdateOperationTest : FunSpec({
	test("two screens share one download and the verified APK remains ready after installer handoff") {
		val file = createTempFile(suffix = ".apk").toFile()
		try {
			val finish = CompletableDeferred<Unit>()
			var downloads = 0
			val operation = AppUpdateOperation { _, progress ->
				downloads++
				progress(42, 100)
				finish.await()
				DownloadResult.Ready(file)
			}
			coroutineScope {
				val first = launch(start = CoroutineStart.UNDISPATCHED) { operation.download(updateFixture()) }
				operation.state.value.downloading shouldBe true
				operation.state.value.progress shouldBe 42
				operation.download(updateFixture()) shouldBe false
				operation.reset() shouldBe false
				downloads shouldBe 1
				finish.complete(Unit)
				first.join()
			}
			operation.install { InstallStartResult.Started } shouldBe InstallStartResult.Started
			operation.state.value.downloading shouldBe false
			operation.state.value.file shouldBe file
			operation.install { InstallStartResult.Started } shouldBe InstallStartResult.Started
			operation.reset() shouldBe true
			operation.state.value.file shouldBe null
		} finally {
			file.delete()
		}
	}

	test("install permission keeps the verified file and a successful retry clears the pending permission") {
		val file = createTempFile(suffix = ".apk").toFile()
		try {
			val operation = AppUpdateOperation { _, _ -> DownloadResult.Ready(file) }
			operation.download(updateFixture()) shouldBe true
			operation.install { InstallStartResult.PermissionRequired } shouldBe InstallStartResult.PermissionRequired
			operation.state.value.awaitingInstallPermission shouldBe true
			operation.state.value.file shouldBe file
			operation.install { InstallStartResult.Started } shouldBe InstallStartResult.Started
			operation.state.value.awaitingInstallPermission shouldBe false
		} finally {
			file.delete()
		}
	}

	test("a failed download restores retry and cannot open the installer") {
		val operation = AppUpdateOperation { _, _ -> DownloadResult.Failed("Network unavailable") }
		operation.download(updateFixture()) shouldBe false
		operation.state.value.downloading shouldBe false
		operation.state.value.message shouldBe "Network unavailable"
		operation.install { error("Installer must not open") } shouldBe null
	}

	test("download completion during playback or in the background keeps the APK ready without opening the installer") {
		val file = createTempFile(suffix = ".apk").toFile()
		try {
			var canInstall = true
			val operation = AppUpdateOperation { _, _ ->
				canInstall = false
				DownloadResult.Ready(file)
			}
			operation.download(updateFixture()) shouldBe true
			operation.install(canInstall) { error("Playback must not be interrupted") } shouldBe null
			operation.state.value.file shouldBe file
			operation.state.value.downloading shouldBe false
			operation.install(true) { InstallStartResult.Started } shouldBe InstallStartResult.Started
		} finally {
			file.delete()
		}
	}

	test("permission-return retry also defers installation when the host is no longer eligible") {
		val file = createTempFile(suffix = ".apk").toFile()
		try {
			val operation = AppUpdateOperation { _, _ -> DownloadResult.Ready(file) }
			operation.download(updateFixture())
			operation.install { InstallStartResult.PermissionRequired }
			operation.install(false) { error("Installer must not open") } shouldBe null
			operation.state.value.file shouldBe file
			operation.state.value.awaitingInstallPermission shouldBe false
		} finally {
			file.delete()
		}
	}

	test("cancelling a download releases the operation for a later retry") {
		val operation = AppUpdateOperation { _, _ -> awaitCancellation() }
		coroutineScope {
			val download = launch(start = CoroutineStart.UNDISPATCHED) { operation.download(updateFixture()) }
			operation.state.value.downloading shouldBe true
			download.cancel()
			download.join()
		}
		operation.state.value.downloading shouldBe false
		operation.reset() shouldBe true
	}

	test("unknown download size stays indeterminate") {
		lateinit var operation: AppUpdateOperation
		operation = AppUpdateOperation { _, progress ->
			progress(42, 0)
			operation.state.value.downloading shouldBe true
			operation.state.value.progress shouldBe null
			DownloadResult.Failed("Offline")
		}
		operation.download(updateFixture()) shouldBe false
		operation.state.value.progress shouldBe null
	}

	test("switching to stable releases rejects a previously selected beta") {
		updateMatchesSelection(updateFixture().copy(prerelease = true), "jellyfin-androidtv-thor-", "-universal-debug.apk", false) shouldBe false
		updateMatchesSelection(updateFixture().copy(prerelease = true), "jellyfin-androidtv-thor-", "-universal-debug.apk", true) shouldBe true
		updateMatchesSelection(updateFixture(), "jellyfin-androidtv-thor-", "-universal-debug.apk", false) shouldBe true
	}

	test("switching APK variant rejects a previously selected universal APK") {
		updateMatchesSelection(updateFixture(), "jellyfin-androidtv-thor-", "-arm64-v8a-debug.apk", false) shouldBe false
	}
})

private fun updateFixture() = AppUpdate(
	versionName = "2026.10.06", buildType = "debug", releaseName = "Update", tagName = "v2026.10.06",
	releaseUrl = "https://github.com/thor2002ro/jellyfin-androidtv/releases/tag/v2026.10.06",
	releaseNotes = "Changes", prerelease = false, publishedAt = "2026-10-06T00:00:00Z",
	assetName = "jellyfin-androidtv-thor-2026.10.06-universal-debug.apk",
	assetUrl = "https://example.invalid/update.apk", assetSize = 100, assetDigest = "sha256:${"0".repeat(64)}",
)
