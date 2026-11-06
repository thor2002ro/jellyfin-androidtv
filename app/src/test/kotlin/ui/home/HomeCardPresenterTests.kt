package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.constant.ImageType

class HomeCardPresenterTests : FunSpec({
	test("default home cards retain the existing poster presentation") {
		val presenter = createHomeCardPresenter(useWideCards = false)

		presenter.showInfo shouldBe true
		presenter.imageType shouldBe ImageType.POSTER
		presenter.staticHeight shouldBe 150
		presenter.uniformAspect shouldBe false
	}

	test("wide home cards use compact landscape artwork") {
		val presenter = createHomeCardPresenter(useWideCards = true)

		presenter.showInfo shouldBe true
		presenter.imageType shouldBe ImageType.THUMB
		presenter.staticHeight shouldBe 120
		presenter.uniformAspect shouldBe false
	}
})
