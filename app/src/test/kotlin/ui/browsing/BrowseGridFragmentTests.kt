package org.jellyfin.androidtv.ui.browsing

import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import androidx.leanback.widget.Presenter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jellyfin.androidtv.data.model.FilterOptions
import org.jellyfin.androidtv.data.model.PlaybackFilter
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.constant.GridDirection
import org.jellyfin.androidtv.constant.LibraryViewStyle
import org.jellyfin.androidtv.ui.presentation.ComposeBrowseListPresenter
import org.jellyfin.androidtv.ui.presentation.ComposeVerticalGridPresenter
import org.jellyfin.androidtv.ui.presentation.HorizontalGridPresenter
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

class BrowseGridFragmentTests : FunSpec({
	test("selecting active sort reverses its direction") {
		selectSort(
			LibrarySortState(ItemSortBy.SORT_NAME, SortOrder.ASCENDING),
			LibrarySortOption(ItemSortBy.SORT_NAME, SortOrder.ASCENDING),
		) shouldBe LibrarySortState(ItemSortBy.SORT_NAME, SortOrder.DESCENDING)
	}

	test("selecting another field uses its natural direction") {
		selectSort(
			LibrarySortState(ItemSortBy.SORT_NAME, SortOrder.DESCENDING),
			LibrarySortOption(ItemSortBy.DATE_CREATED, SortOrder.DESCENDING),
		) shouldBe LibrarySortState(ItemSortBy.DATE_CREATED, SortOrder.DESCENDING)
	}

	test("sort direction renders a compact menu arrow") {
		SortOrder.ASCENDING.menuArrow shouldBe "↑"
		SortOrder.DESCENDING.menuArrow shouldBe "↓"
	}

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

	test("dense list overrides the saved card direction") {
		BrowseGridFragment.createGridPresenter(
			GridDirection.HORIZONTAL,
			LibraryViewStyle.DENSE_LIST,
		).javaClass shouldBe ComposeBrowseListPresenter::class.java
	}

	test("dense list uses vertical navigation controls while cards honor their direction") {
		BrowseGridFragment.effectiveNavigationControlsDirection(
			LibraryViewStyle.DENSE_LIST,
			GridDirection.HORIZONTAL,
		) shouldBe GridDirection.VERTICAL
		BrowseGridFragment.effectiveNavigationControlsDirection(
			LibraryViewStyle.CARDS,
			GridDirection.HORIZONTAL,
		) shouldBe GridDirection.HORIZONTAL
	}

	test("fresh sort and filter queries choose only valid positions") {
		BrowseGridFragment.nextSelectionAfterQueryChange(50) shouldBe 0
		BrowseGridFragment.nextSelectionAfterQueryChange(0) shouldBe -1
	}

	test("empty results keep filter clearing reachable") {
		BrowseGridFragment.shouldKeepToolbarFocusable(0, true) shouldBe true
		BrowseGridFragment.shouldKeepToolbarFocusable(0, false) shouldBe false
	}

	test("watched filter removes an item refreshed as unplayed") {
		val filters = FilterOptions(playback = PlaybackFilter.WATCHED)

		BrowseGridFragment.shouldRemoveFilteredItem(filters, true, false) shouldBe true
		BrowseGridFragment.shouldRemoveFilteredItem(filters, true, true) shouldBe false
	}

	test("unchanged filters do not start another library query") {
		val current = FilterOptions(playback = PlaybackFilter.UNWATCHED)

		BrowseGridFragment.shouldApplyLibraryFilters(current, current.copy()) shouldBe false
		BrowseGridFragment.shouldApplyLibraryFilters(current, FilterOptions()) shouldBe true
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
		BrowseGridFragment.getGridHostBottomMarginDp(GridDirection.VERTICAL) shouldBe 24
	}

	test("alphabet rail follows the grid direction without a sort gate") {
		BrowseGridFragment.shouldShowAlphabetPicker(GridDirection.HORIZONTAL, GridDirection.HORIZONTAL) shouldBe true
		BrowseGridFragment.shouldShowAlphabetPicker(GridDirection.VERTICAL, GridDirection.VERTICAL) shouldBe true
		BrowseGridFragment.shouldShowAlphabetPicker(GridDirection.HORIZONTAL, GridDirection.VERTICAL) shouldBe false
		BrowseGridFragment.shouldShowAlphabetPicker(GridDirection.VERTICAL, GridDirection.HORIZONTAL) shouldBe false
	}

	test("horizontal grid handoff routes up to customization") {
		BrowseGridFragment.shouldMoveFocusFromGridToToolbar(
			GridDirection.HORIZONTAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_UP,
		) shouldBe true
		BrowseGridFragment.shouldMoveFocusFromGridToToolbar(
			GridDirection.HORIZONTAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_DOWN,
		) shouldBe false
		BrowseGridFragment.shouldMoveFocusFromGridToToolbar(
			GridDirection.HORIZONTAL,
			KeyEvent.ACTION_UP,
			KeyEvent.KEYCODE_DPAD_UP,
		) shouldBe false
		BrowseGridFragment.shouldMoveFocusFromGridToToolbar(
			GridDirection.VERTICAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_UP,
		) shouldBe false
	}

	test("vertical grid handoff routes up to home") {
		BrowseGridFragment.shouldMoveFocusFromGridToHome(
			GridDirection.VERTICAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_UP,
		) shouldBe true
		BrowseGridFragment.shouldMoveFocusFromGridToHome(
			GridDirection.VERTICAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_DOWN,
		) shouldBe false
		BrowseGridFragment.shouldMoveFocusFromGridToHome(
			GridDirection.HORIZONTAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_UP,
		) shouldBe false
	}

	test("custom navigation control focus routing is horizontal only") {
		BrowseGridFragment.shouldConfigureNavigationControlsFocus(GridDirection.HORIZONTAL) shouldBe true
		BrowseGridFragment.shouldConfigureNavigationControlsFocus(GridDirection.VERTICAL) shouldBe false
	}

	test("horizontal customization routes up to home") {
		BrowseGridFragment.shouldMoveFocusFromToolbarToHome(
			GridDirection.HORIZONTAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_UP,
		) shouldBe true
		BrowseGridFragment.shouldMoveFocusFromToolbarToHome(
			GridDirection.HORIZONTAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_DOWN,
		) shouldBe false
		BrowseGridFragment.shouldMoveFocusFromToolbarToHome(
			GridDirection.VERTICAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_UP,
		) shouldBe false
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
