package org.jellyfin.androidtv.ui.browsing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.ui.presentation.CardPresenter

class EnhancedBrowseCardPresenterTests : FunSpec({
	test("TV browse Next Up uses compact landscape cards without changing other rows") {
		val defaultPresenter = CardPresenter(
			showInfo = false,
			imageType = ImageType.POSTER,
			staticHeight = 140,
			uniformAspect = false,
		)

		val nextUpPresenter = EnhancedBrowseFragment.cardPresenterForRow(QueryType.NextUp, defaultPresenter)
		val latestPresenter = EnhancedBrowseFragment.cardPresenterForRow(QueryType.LatestItems, defaultPresenter)

		nextUpPresenter.showInfo shouldBe false
		nextUpPresenter.imageType shouldBe ImageType.THUMB
		nextUpPresenter.staticHeight shouldBe 120
		nextUpPresenter.uniformAspect shouldBe false
		latestPresenter shouldBe defaultPresenter
	}
})
