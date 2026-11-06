package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

class ImageHelperTests : FunSpec({
	val imageHelper = ImageHelper(mockk<ApiClient>())

	test("primary image selection preserves its BlurHash metadata") {
		val itemId = UUID.randomUUID()
		val item = BaseItemDto(
			id = itemId,
			type = BaseItemKind.MOVIE,
			imageTags = mapOf(ImageType.PRIMARY to "primary-tag"),
			imageBlurHashes = mapOf(ImageType.PRIMARY to mapOf("primary-tag" to "primary-hash")),
			primaryImageAspectRatio = 2.0 / 3.0,
		)

		val image = imageHelper.getPrimaryImage(item, preferParentThumb = false)

		image?.item shouldBe itemId
		image?.type shouldBe ImageType.PRIMARY
		image?.blurHash shouldBe "primary-hash"
		image?.aspectRatio shouldBe (2f / 3f)
	}

	test("logo selection preserves parent BlurHash metadata") {
		val parentId = UUID.randomUUID()
		val item = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.EPISODE,
			parentLogoItemId = parentId,
			parentLogoImageTag = "logo-tag",
			imageBlurHashes = mapOf(ImageType.LOGO to mapOf("logo-tag" to "logo-hash")),
		)

		val image = imageHelper.getLogoImage(item)

		image?.item shouldBe parentId
		image?.type shouldBe ImageType.LOGO
		image?.blurHash shouldBe "logo-hash"
	}

	test("series thumbnail selection uses thumbnail BlurHash metadata") {
		val seriesId = UUID.randomUUID()
		val item = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.EPISODE,
			seriesId = seriesId,
			seriesThumbImageTag = "thumb-tag",
			imageBlurHashes = mapOf(ImageType.THUMB to mapOf("thumb-tag" to "thumb-hash")),
		)

		val image = imageHelper.getPrimaryImage(item, preferParentThumb = true)

		image?.item shouldBe seriesId
		image?.type shouldBe ImageType.THUMB
		image?.blurHash shouldBe "thumb-hash"
	}
})
