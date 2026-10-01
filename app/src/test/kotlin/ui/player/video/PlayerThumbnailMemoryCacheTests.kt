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
import org.jellyfin.androidtv.util.apiclient.TrickplayImage
import org.jellyfin.androidtv.util.apiclient.TrickplayTileSheet

class PlayerThumbnailMemoryCacheTests : FunSpec({
	test("player thumbnail cache is limited to 15 MiB") {
		PLAYER_THUMBNAIL_CACHE_MAX_BYTES shouldBe 15 * 1024 * 1024
	}

	test("scaled trickplay crops preserve the source tile position") {
		calculateTrickplayThumbnailCrop(
			image = TrickplayImage(
				sheet = trickplaySheet(columns = 10, rows = 10),
				offsetX = 2_880,
				offsetY = 1_188,
				width = 320,
				height = 132,
			),
			decodedSheetWidth = 1_600,
			decodedSheetHeight = 660,
		) shouldBe TrickplayThumbnailCrop(
			x = 1_440,
			y = 594,
			width = 160,
			height = 66,
		)
	}

	test("prefetch keeps the first sheets that fit instead of downloading sheets it immediately evicts") {
		val sheets = (0 until 8).map { index -> trickplaySheet(url = "sheet-$index") }

		selectTrickplaySheetsToPrefetch(sheets) shouldBe sheets.take(5)
	}

	test("trickplay prefetch decodes 160 by 90 RGB565 tiles without Coil caches") {
		val request = TrickplayTileSheet(
			url = "https://example.test/trickplay.jpg",
			headers = NetworkHeaders.Builder().build(),
			columns = 10,
			rows = 10,
		).buildPlayerThumbnailRequest(mockk(relaxed = true))

		request.sizeResolver.size() shouldBe Size(1_600, 900)
		request.bitmapConfig shouldBe Bitmap.Config.RGB_565
		request.allowHardware shouldBe false
		request.memoryCachePolicy shouldBe CachePolicy.DISABLED
		request.diskCachePolicy shouldBe CachePolicy.DISABLED
		request.transformations shouldBe emptyList()
	}
})

private fun trickplaySheet(
	url: String = "https://example.test/trickplay.jpg",
	columns: Int = 10,
	rows: Int = 10,
) = TrickplayTileSheet(
	url = url,
	headers = NetworkHeaders.Builder().build(),
	columns = columns,
	rows = rows,
)
