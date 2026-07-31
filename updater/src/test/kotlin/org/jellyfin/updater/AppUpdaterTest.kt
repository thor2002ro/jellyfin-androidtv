package org.jellyfin.updater

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class AppUpdaterTest : FunSpec({
	test("defaults to the installed APK variant") {
		defaultUseUniversalApk(setOf("arm64-v8a"), "arm64-v8a") shouldBe false
		defaultUseUniversalApk(ARTIFACT_ABIS.toSet(), "arm64-v8a") shouldBe true
		defaultUseUniversalApk(setOf("arm64-v8a"), null) shouldBe true
	}

	test("selects the primary supported device ABI") {
		selectDeviceAbi(listOf("arm64-v8a", "armeabi-v7a"), ARTIFACT_ABIS) shouldBe "arm64-v8a"
		selectDeviceAbi(listOf("unsupported"), ARTIFACT_ABIS) shouldBe null
		selectDeviceAbi(listOf("arm64-v8a"), listOf("x86")) shouldBe null
	}

	test("reads installed ABIs from APK library entries") {
		val directory = createTempDirectory().toFile()
		try {
			val apk = directory.resolve("app.apk")
			ZipOutputStream(apk.outputStream()).use { zip ->
				listOf("lib/", "lib/arm64-v8a/liba.so", "lib/arm64-v8a/libb.so", "lib/x86/liba.so", "assets/data").forEach {
					zip.putNextEntry(ZipEntry(it))
					zip.closeEntry()
				}
			}

			readInstalledAbis(apk) shouldBe setOf("arm64-v8a", "x86")
		} finally {
			directory.deleteRecursively()
		}
	}

	test("switches between universal and device ABI artifacts") {
		artifactSuffixForSelection(useUniversal = true, deviceAbi = "arm64-v8a", buildType = "release") shouldBe
			"-universal-release.apk"
		artifactSuffixForSelection(useUniversal = false, deviceAbi = "arm64-v8a", buildType = "release") shouldBe
			"-arm64-v8a-release.apk"
		artifactSuffixForSelection(useUniversal = false, deviceAbi = null, buildType = "release") shouldBe
			"-universal-release.apk"
	}

	test("rejects incomplete downloads") {
		downloadSizeError(999, 1_000) shouldBe "Downloaded 999 of 1000 bytes"
		downloadSizeError(1_000, 1_000) shouldBe null
		downloadSizeError(1_000, 0) shouldBe null
	}

	test("accepts the same signer and forward certificate rotation") {
		signerDigestsMatch(SignerDigests(setOf("a")), SignerDigests(setOf("a"))) shouldBe true
		signerDigestsMatch(
			SignerDigests(current = setOf("a")),
			SignerDigests(current = setOf("b"), history = setOf("a", "b")),
		) shouldBe true
	}

	test("rejects unrelated signers, rollback, and changed multi-signer sets") {
		signerDigestsMatch(SignerDigests(setOf("a")), SignerDigests(setOf("b"))) shouldBe false
		signerDigestsMatch(
			SignerDigests(current = setOf("b"), history = setOf("a", "b")),
			SignerDigests(current = setOf("a")),
		) shouldBe false
		signerDigestsMatch(
			SignerDigests(current = setOf("a", "b"), hasMultipleSigners = true),
			SignerDigests(current = setOf("a", "c"), hasMultipleSigners = true),
		) shouldBe false
		signerDigestsMatch(SignerDigests(emptySet()), SignerDigests(emptySet())) shouldBe false
	}

	test("promotes a completed download") {
		val directory = createTempDirectory().toFile()
		try {
			val partialFile = directory.resolve("update.apk.part").apply { writeText("new") }
			val file = directory.resolve("update.apk")

			promoteDownload(partialFile, file)

			file.readText() shouldBe "new"
			partialFile.exists() shouldBe false
		} finally {
			directory.deleteRecursively()
		}
	}

	test("prunes stale updater APKs but keeps the active download") {
		val directory = createTempDirectory().toFile()
		try {
			val active = directory.resolve("active.apk").apply { writeText("active") }
			val partial = directory.resolve("active.apk.part").apply { writeText("partial") }
			val stale = directory.resolve("stale.apk").apply { writeText("stale") }
			val stalePartial = directory.resolve("stale.apk.part").apply { writeText("stale") }
			val unrelated = directory.resolve("note.txt").apply { writeText("keep") }

			pruneUpdateCache(directory, active, partial)

			active.exists() shouldBe true
			partial.exists() shouldBe true
			stale.exists() shouldBe false
			stalePartial.exists() shouldBe false
			unrelated.exists() shouldBe true
		} finally {
			directory.deleteRecursively()
		}
	}
})

private val ARTIFACT_ABIS = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
