package org.jellyfin.androidtv.ui.settings

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.LibraryCardSpacing

class LibraryDisplayOptionTests : FunSpec({
	test("library card spacing defaults preserve the current layout") {
		LibraryCardSpacing.NORMAL.nameRes shouldBe R.string.library_card_spacing_normal
	}

	test("library card spacing has a dedicated dialog route") {
		Routes.LIBRARIES_DISPLAY_SPACING shouldBe "/libraries/display/{itemId}/{displayPreferencesId}/spacing"
	}
})
