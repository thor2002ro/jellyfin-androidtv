package org.jellyfin.androidtv.ui.settings

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.LibraryCardSpacing
import org.jellyfin.androidtv.constant.LibraryViewStyle
import org.jellyfin.androidtv.ui.settings.screen.library.cardDisplayOptionsVisible

class LibraryDisplayOptionTests : FunSpec({
	test("library browser display defaults preserve current layout") {
		LibraryViewStyle.CARDS.nameRes shouldBe R.string.library_view_style_cards
		LibraryCardSpacing.NORMAL.nameRes shouldBe R.string.library_card_spacing_normal
	}

	test("library display options have dedicated dialog routes") {
		Routes.LIBRARIES_DISPLAY_VIEW_STYLE shouldBe "/libraries/display/{itemId}/{displayPreferencesId}/view-style"
		Routes.LIBRARIES_DISPLAY_SPACING shouldBe "/libraries/display/{itemId}/{displayPreferencesId}/spacing"
	}

	test("dense list hides card-only display options") {
		cardDisplayOptionsVisible(LibraryViewStyle.CARDS) shouldBe true
		cardDisplayOptionsVisible(LibraryViewStyle.DENSE_LIST) shouldBe false
	}
})
