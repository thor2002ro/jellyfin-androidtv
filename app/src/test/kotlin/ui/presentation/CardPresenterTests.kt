package org.jellyfin.androidtv.ui.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.constant.LibraryViewStyle
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

class CardPresenterTests : FunSpec({
	test("card title preference affects cards while dense list remains titled") {
		shouldShowBrowseCardInfo(LibraryViewStyle.CARDS, showCardTitles = false) shouldBe false
		shouldShowBrowseCardInfo(LibraryViewStyle.CARDS, showCardTitles = true) shouldBe true
		shouldShowBrowseCardInfo(LibraryViewStyle.DENSE_LIST, showCardTitles = false) shouldBe true
	}

	test("static-height poster folders preserve wide artwork") {
		val rowItem = BaseItemDtoBaseRowItem(
			BaseItemDto(
				id = UUID.randomUUID(),
				type = BaseItemKind.FOLDER,
				primaryImageAspectRatio = 16.0 / 9.0,
			)
		)
		val method = Class.forName("org.jellyfin.androidtv.ui.presentation.CardPresenterKt")
			.getDeclaredMethod("getDisplayConfig", org.jellyfin.androidtv.ui.itemhandling.BaseRowItem::class.java, ImageType::class.java, Boolean::class.javaPrimitiveType)
			.apply { isAccessible = true }
		val config = method.invoke(null, rowItem, ImageType.POSTER, true)
		val aspectRatio = config.javaClass.getDeclaredMethod("getAspectRatio").invoke(config) as Float

		aspectRatio shouldBe (16f / 9f)
	}
})
