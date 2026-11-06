package org.jellyfin.androidtv.ui.presentation

import coil3.compose.AsyncImagePainter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.design.Tokens
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

class CardPresenterDisplayConfigTests : FunSpec({
	test("library name stays centered until image loading succeeds") {
		for (type in listOf(BaseItemKind.USER_VIEW, BaseItemKind.COLLECTION_FOLDER)) {
			val rowItem = BaseItemDtoBaseRowItem(BaseItemDto(
				id = UUID.randomUUID(),
				type = type,
				imageTags = mapOf(org.jellyfin.sdk.model.api.ImageType.PRIMARY to "stale-server-tag"),
			))

			rowItem.showLibraryTextFallback(usePreview = false, imageState = AsyncImagePainter.State.Empty) shouldBe true
			rowItem.showLibraryTextFallback(usePreview = false, imageState = AsyncImagePainter.State.Loading(null)) shouldBe true
			rowItem.showLibraryTextFallback(usePreview = false, imageState = AsyncImagePainter.State.Error(null, mockk())) shouldBe true
			rowItem.showLibraryTextFallback(usePreview = false, imageState = AsyncImagePainter.State.Success(mockk(), mockk())) shouldBe false
			rowItem.getDisplayConfig(ImageType.POSTER, uniformAspect = false).backgroundColor shouldBe Tokens.Color.colorBlue800
		}
	}

	test("missing library image keeps its centered name") {
		val rowItem = BaseItemDtoBaseRowItem(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.USER_VIEW))

		rowItem.showLibraryTextFallback(usePreview = false, imageState = AsyncImagePainter.State.Empty) shouldBe true
	}

	test("library fallback does not duplicate preview captions or change non-library cards") {
		val library = BaseItemDtoBaseRowItem(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.USER_VIEW))
		val video = BaseItemDtoBaseRowItem(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.VIDEO))

		library.showLibraryTextFallback(usePreview = true, imageState = AsyncImagePainter.State.Empty) shouldBe false
		video.showLibraryTextFallback(usePreview = false, imageState = AsyncImagePainter.State.Empty) shouldBe false
	}

	test("display config resolves invalid aspect ratios safely") {
		BaseRowItemDisplayConfig(
			image = null,
			iconRes = 0,
			aspectRatio = 0f,
		).resolvedAspectRatio() shouldBe 1f
	}

	test("series poster preference cannot force a portrait ratio on wide episode cards") {
		val rowItem = BaseItemDtoBaseRowItem(
			item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE),
			preferParentThumb = true,
			preferSeriesPoster = true,
		)

		rowItem.getDisplayConfig(ImageType.THUMB, uniformAspect = false).aspectRatio shouldBe
			ImageHelper.ASPECT_RATIO_16_9.toFloat()
		rowItem.getDisplayConfig(ImageType.POSTER, uniformAspect = false).aspectRatio shouldBe
			ImageHelper.ASPECT_RATIO_2_3.toFloat()
	}

	test("library cards use server artwork without adding a second name over it") {
		for (type in listOf(BaseItemKind.USER_VIEW, BaseItemKind.COLLECTION_FOLDER)) {
			val rowItem = BaseItemDtoBaseRowItem(BaseItemDto(
				id = UUID.randomUUID(),
				type = type,
				imageTags = mapOf(org.jellyfin.sdk.model.api.ImageType.PRIMARY to "server-artwork"),
			))

			rowItem.getImage(ImageType.POSTER)?.tag shouldBe "server-artwork"
			rowItem.showCardInfoOverlay shouldBe false
		}
	}

	test("libraries without server artwork retain their fallback name") {
		for (type in listOf(BaseItemKind.USER_VIEW, BaseItemKind.COLLECTION_FOLDER)) {
			val rowItem = BaseItemDtoBaseRowItem(BaseItemDto(id = UUID.randomUUID(), type = type))

			rowItem.getImage(ImageType.POSTER) shouldBe null
			rowItem.showCardInfoOverlay shouldBe true
		}
	}

	test("other artwork cards keep their existing title overlay") {
		val rowItem = BaseItemDtoBaseRowItem(BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.VIDEO,
			imageTags = mapOf(org.jellyfin.sdk.model.api.ImageType.PRIMARY to "video-artwork"),
		))

		rowItem.showCardInfoOverlay shouldBe true
	}
})
