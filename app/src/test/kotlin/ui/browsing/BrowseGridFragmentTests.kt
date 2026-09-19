package org.jellyfin.androidtv.ui.browsing

import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.constant.GridDirection
import org.jellyfin.androidtv.ui.presentation.ComposeVerticalGridPresenter
import org.jellyfin.androidtv.ui.presentation.HorizontalGridPresenter
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

class BrowseGridFragmentTests : FunSpec({
	test("safe selected position never returns invalid leanback positions") {
		BrowseGridFragment.getSafeSelectedPosition(-1, -1, -1, 125) shouldBe 0
		BrowseGridFragment.getSafeSelectedPosition(21, 6, 6, 80) shouldBe 21
		BrowseGridFragment.getSafeSelectedPosition(150, -1, -1, 125) shouldBe 124
		BrowseGridFragment.getSafeSelectedPosition(-1, 150, -1, 125) shouldBe 124
		BrowseGridFragment.getSafeSelectedPosition(-1, -1, 150, 125) shouldBe 124
		BrowseGridFragment.getSafeSelectedPosition(-1, -1, -1, 0) shouldBe -1
	}

	test("browse grid loads lightweight fields before stream badges") {
		val library = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.USER_VIEW,
			collectionType = CollectionType.MOVIES,
		)

		BrowsingUtils.createBrowseGridItemsRequest(library).fields shouldBe ItemRepository.browseFields
	}

	test("vertical browse uses a packed grid for mixed card widths") {
		BrowseGridFragment.createGridPresenter(GridDirection.VERTICAL).javaClass shouldBe ComposeVerticalGridPresenter::class.java
	}

	test("horizontal browse uses the Compose grid presenter") {
		BrowseGridFragment.createGridPresenter(GridDirection.HORIZONTAL).javaClass shouldBe HorizontalGridPresenter::class.java
	}

	test("vertical browse keeps the alphabet rail compact while horizontal stays edge aligned") {
		BrowseGridFragment.getGridHostSideMarginDp(GridDirection.VERTICAL) shouldBe 20
		BrowseGridFragment.getGridHostSideMarginDp(GridDirection.HORIZONTAL) shouldBe 0
	}

	test("vertical browse gives the customization rail extra clearance") {
		BrowseGridFragment.getGridHostEndMarginDp(GridDirection.VERTICAL) shouldBe 28
		BrowseGridFragment.getGridHostEndMarginDp(GridDirection.HORIZONTAL) shouldBe 0
	}

	test("horizontal browse converts XML pixel insets before passing them to Compose") {
		BrowseGridFragment.pixelsToDp(100, 2f) shouldBe 50
	}

	test("browse grid keeps the appropriate bottom control band clear of cards") {
		BrowseGridFragment.getGridHostBottomMarginDp(GridDirection.HORIZONTAL) shouldBe 35
		BrowseGridFragment.getGridHostBottomMarginDp(GridDirection.VERTICAL) shouldBe 6
	}

	test("alphabet rail follows the grid direction without a sort gate") {
		BrowseGridFragment.shouldShowAlphabetPicker(GridDirection.HORIZONTAL, GridDirection.HORIZONTAL) shouldBe true
		BrowseGridFragment.shouldShowAlphabetPicker(GridDirection.VERTICAL, GridDirection.VERTICAL) shouldBe true
		BrowseGridFragment.shouldShowAlphabetPicker(GridDirection.HORIZONTAL, GridDirection.VERTICAL) shouldBe false
		BrowseGridFragment.shouldShowAlphabetPicker(GridDirection.VERTICAL, GridDirection.HORIZONTAL) shouldBe false
	}

	test("replacing a browse grid releases its bound presenter") {
		val presenter = TrackingGridPresenter()
		val holder = Presenter.ViewHolder(mockk<View>(relaxed = true))
		presenter.onBindViewHolder(holder, Unit)

		BrowseGridFragment.releaseGrid(presenter, holder)

		presenter.bound shouldBe false
	}

})

private class TrackingGridPresenter : Presenter() {
	var bound = false
		private set

	override fun onCreateViewHolder(parent: ViewGroup) = ViewHolder(mockk<View>(relaxed = true))

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		bound = true
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) {
		bound = false
	}
}
