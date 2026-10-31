package org.jellyfin.androidtv.ui.player.video

import coil3.network.NetworkHeaders
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.util.apiclient.TrickplayImage
import org.jellyfin.androidtv.util.apiclient.TrickplayTileSheet

class PlayerThumbnailMemoryCacheTests : FunSpec({
	test("player thumbnail cache is limited to 30 MiB") {
		PLAYER_THUMBNAIL_CACHE_MAX_BYTES shouldBe 30 * 1024 * 1024
	}

	test("decoded sheets over the memory limit are rejected") {
		isTrickplaySheetWithinMemoryLimit(PLAYER_THUMBNAIL_CACHE_MAX_BYTES) shouldBe true
		isTrickplaySheetWithinMemoryLimit(PLAYER_THUMBNAIL_CACHE_MAX_BYTES + 1) shouldBe false
	}

	test("scaled trickplay crops preserve the source tile position") {
		calculateTrickplayThumbnailCrop(
			image = TrickplayImage(
				sheet = trickplaySheet(columns = 10, rows = 10),
				offsetX = 5_760,
				offsetY = 2_376,
				width = 640,
				height = 264,
			),
			decodedSheetWidth = 3_200,
			decodedSheetHeight = 1_320,
		) shouldBe TrickplayThumbnailCrop(
			x = 2_880,
			y = 1_188,
			width = 320,
			height = 132,
		)
	}

	test("prefetch leaves room for one on-demand sheet") {
		val sheets = (0 until 8).map { index -> trickplaySheet(url = "sheet-$index") }

		selectTrickplaySheetsToPrefetch(sheets) shouldBe sheets.take(1)
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
