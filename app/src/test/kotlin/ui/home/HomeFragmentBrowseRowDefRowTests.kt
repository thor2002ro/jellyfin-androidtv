package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.Row
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest

class HomeFragmentBrowseRowDefRowTests : FunSpec({
	test("resume home row uses the configured wide card presenter") {
		val cardPresenter = CardPresenter(
			showInfo = true,
			imageType = ImageType.THUMB,
			staticHeight = 120,
			uniformAspect = false,
		)
		val browseRowDef = BrowseRowDef(
			header = "Continue Watching",
			query = GetResumeItemsRequest(),
			chunkSize = 0,
			preferParentThumb = false,
			staticHeight = true,
			changeTriggers = emptyArray<ChangeTriggerType>(),
		)

		val adapter = createResumeHomeRowAdapter(
			context = mockk<Context>(),
			browseRowDef = browseRowDef,
			cardPresenter = cardPresenter,
			rowsAdapter = MutableObjectAdapter<Row>(),
		)

		val presenter = adapter.presenterSelector.getPresenter(Unit) as CardPresenter
		presenter.imageType shouldBe ImageType.THUMB
		presenter.staticHeight shouldBe 120
	}
})
