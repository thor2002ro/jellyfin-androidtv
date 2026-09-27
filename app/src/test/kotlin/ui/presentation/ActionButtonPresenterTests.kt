package org.jellyfin.androidtv.ui.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

class ActionButtonPresenterTests : FunSpec({
	test("single action buttons reserve icon space only when an icon exists") {
		ActionButtonSize.SINGLE.horizontal shouldBe true
		ActionButtonSize.SINGLE.heightDp shouldBe 39
		ActionButtonSize.SINGLE.labelStartMarginDp(hasIcon = true) shouldBe 35
		ActionButtonSize.SINGLE.labelStartMarginDp(hasIcon = false) shouldBe 0
	}

	test("double action buttons keep the current stacked layout") {
		ActionButtonSize.DOUBLE.horizontal shouldBe false
		ActionButtonSize.DOUBLE.widthDp shouldBe 126
		ActionButtonSize.DOUBLE.heightDp shouldBe 78
		ActionButtonSize.DOUBLE.labelStartMarginDp(hasIcon = true) shouldBe 0
	}

	test("custom action button dimensions remain available") {
		ActionButtonPresenter(widthDp = 168, heightDp = 104)
		shouldThrow<IllegalArgumentException> { ActionButtonPresenter(widthDp = 0, heightDp = 104) }
	}
})
