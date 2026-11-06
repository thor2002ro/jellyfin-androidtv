package org.jellyfin.androidtv.util.coil

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class AppCacheVersionTests : FunSpec({
	test("a changed app version clears the app cache once and records the current version") {
		var clearCount = 0
		var recordedVersion: Long? = null

		clearCacheForVersionChange(
			previousVersionCode = 100,
			currentVersionCode = 101,
			clearCache = {
				clearCount++
				true
			},
			recordCurrentVersion = {
				recordedVersion = it
				true
			},
		) shouldBe true

		clearCount shouldBe 1
		recordedVersion shouldBe 101
	}

	test("the same app version leaves the app cache untouched") {
		var clearCount = 0
		var recordedVersion: Long? = null

		clearCacheForVersionChange(
			previousVersionCode = 101,
			currentVersionCode = 101,
			clearCache = {
				clearCount++
				true
			},
			recordCurrentVersion = {
				recordedVersion = it
				true
			},
		) shouldBe false

		clearCount shouldBe 0
		recordedVersion shouldBe null
	}

	test("a failed cache clear does not record the version so startup can retry") {
		var recordedVersion: Long? = null

		clearCacheForVersionChange(
			previousVersionCode = 100,
			currentVersionCode = 101,
			clearCache = { false },
			recordCurrentVersion = {
				recordedVersion = it
				true
			},
		) shouldBe false

		recordedVersion shouldBe null
	}

	test("all cache directory contents are detached without touching application data") {
		val root = createTempDirectory("app-cache-version-test").toFile()
		try {
			val cacheDirectory = root.resolve("cache").apply { mkdirs() }
			cacheDirectory.resolve("image_cache").apply { mkdirs() }.resolve("cached-image").writeText("image")
			cacheDirectory.resolve("updater").apply { mkdirs() }.resolve("update.apk").writeText("update")
			cacheDirectory.resolve("fontconfig").apply { mkdirs() }.resolve("fonts.cache").writeText("font")
			cacheDirectory.resolve("shader-cache").writeText("shader")
			val applicationData = root.resolve("shared_prefs").apply { mkdirs() }.resolve("user.xml")
			applicationData.writeText("account and settings")
			val staleCache = cacheDirectory.resolve(".app-cache-stale-test")

			detachCacheContents(cacheDirectory, staleCache) shouldBe true

			cacheDirectory.resolve("image_cache").exists() shouldBe false
			cacheDirectory.resolve("updater").exists() shouldBe false
			cacheDirectory.resolve("fontconfig").exists() shouldBe false
			cacheDirectory.resolve("shader-cache").exists() shouldBe false
			staleCache.resolve("image_cache/cached-image").readText() shouldBe "image"
			staleCache.resolve("updater/update.apk").readText() shouldBe "update"
			staleCache.resolve("fontconfig/fonts.cache").readText() shouldBe "font"
			staleCache.resolve("shader-cache").readText() shouldBe "shader"
			applicationData.readText() shouldBe "account and settings"
		} finally {
			root.deleteRecursively()
		}
	}
})
