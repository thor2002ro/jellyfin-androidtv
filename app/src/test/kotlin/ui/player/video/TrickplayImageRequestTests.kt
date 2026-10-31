package org.jellyfin.androidtv.ui.player.video

import android.graphics.Bitmap
import coil3.network.NetworkHeaders
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.transformations
import coil3.size.Size
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jellyfin.androidtv.util.apiclient.TRICKPLAY_DECODE_MAX_BYTES_PER_PIXEL
import org.jellyfin.androidtv.util.apiclient.TRICKPLAY_SHEET_MAX_BYTES
import org.jellyfin.androidtv.util.apiclient.TrickplayTileSheet
import org.jellyfin.androidtv.util.apiclient.isValidTrickplayGeometry

class TrickplayImageRequestTests : FunSpec({
	test("oversized sheets stay within the decode budget") {
		val sheet = TrickplayTileSheet(
			url = "https://example.test/trickplay.jpg",
			headers = NetworkHeaders.Builder().build(),
			columns = 20,
			rows = 20,
		)

		(sheet.decodeWidth.toLong() * sheet.decodeHeight * TRICKPLAY_DECODE_MAX_BYTES_PER_PIXEL) shouldBeLessThanOrEqual TRICKPLAY_SHEET_MAX_BYTES.toLong()
		sheet.decodeWidth % sheet.columns shouldBe 0
		sheet.decodeHeight % sheet.rows shouldBe 0
	}

	test("server geometry rejects pixel offsets and sheet counts that can overflow") {
		isValidTrickplayGeometry(320, 180, 10, 10, 1_000) shouldBe true
		isValidTrickplayGeometry(Int.MAX_VALUE, 180, 2, 1, 1) shouldBe false
		isValidTrickplayGeometry(320, Int.MAX_VALUE, 1, 2, 1) shouldBe false
		isValidTrickplayGeometry(320, 180, 10, 10, 0) shouldBe false
	}

	test("impossible sheet grids are rejected before dimensions overflow") {
		shouldThrow<IllegalArgumentException> {
			TrickplayTileSheet(
				url = "https://example.test/trickplay.jpg",
				headers = NetworkHeaders.Builder().build(),
				columns = Int.MAX_VALUE,
				rows = Int.MAX_VALUE,
			)
		}
	}

	test("on-demand trickplay loads the reduced RGB565 sheet without Coil caches or transformations") {
		val sheet = TrickplayTileSheet(
			url = "https://example.test/trickplay.jpg",
			headers = NetworkHeaders.Builder().build(),
			columns = 10,
			rows = 10,
		)
		val request = sheet.buildPlayerThumbnailRequest(mockk(relaxed = true))

		request.sizeResolver.size() shouldBe Size(3_200, 1_800)
		request.bitmapConfig shouldBe Bitmap.Config.RGB_565
		request.allowHardware shouldBe false
		request.memoryCachePolicy shouldBe CachePolicy.DISABLED
		request.diskCachePolicy shouldBe CachePolicy.DISABLED
		request.transformations shouldBe emptyList()
	}

	test("thumbnail requests use their supplied display size") {
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
