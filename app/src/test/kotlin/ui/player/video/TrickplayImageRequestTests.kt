package org.jellyfin.androidtv.ui.player.video

import android.graphics.Bitmap
import coil3.network.NetworkHeaders
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.transformations
import coil3.size.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jellyfin.androidtv.util.apiclient.TrickplayTileSheet

class TrickplayImageRequestTests : FunSpec({
	test("a ten by ten sheet decodes as 160 by 90 tiles") {
		val sheet = TrickplayTileSheet(
			url = "https://example.test/trickplay.jpg",
			headers = NetworkHeaders.Builder().build(),
			columns = 10,
			rows = 10,
		)

		sheet.decodeWidth shouldBe 1_600
		sheet.decodeHeight shouldBe 900
	}

	test("on-demand trickplay loads the reduced RGB565 sheet without Coil caches or transformations") {
		val sheet = TrickplayTileSheet(
			url = "https://example.test/trickplay.jpg",
			headers = NetworkHeaders.Builder().build(),
			columns = 10,
			rows = 10,
		)
		val request = sheet.buildPlayerThumbnailRequest(mockk(relaxed = true))

		request.sizeResolver.size() shouldBe Size(1_600, 900)
		request.bitmapConfig shouldBe Bitmap.Config.RGB_565
		request.allowHardware shouldBe false
		request.memoryCachePolicy shouldBe CachePolicy.DISABLED
		request.diskCachePolicy shouldBe CachePolicy.DISABLED
		request.transformations shouldBe emptyList()
	}

	test("chapter thumbnails decode at display size as RGB565 without Coil caches") {
		val request = buildPlayerThumbnailRequest(
			context = mockk(relaxed = true),
			url = "https://example.test/chapter.jpg",
			width = 320,
			height = 180,
		)

		request.sizeResolver.size() shouldBe Size(320, 180)
		request.bitmapConfig shouldBe Bitmap.Config.RGB_565
		request.allowHardware shouldBe false
		request.memoryCachePolicy shouldBe CachePolicy.DISABLED
		request.diskCachePolicy shouldBe CachePolicy.DISABLED
		request.transformations shouldBe emptyList()
	}
})
