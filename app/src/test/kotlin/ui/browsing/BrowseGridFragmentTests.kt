package org.jellyfin.androidtv.ui.browsing

import android.view.KeyEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.constant.LibraryViewStyle
import org.jellyfin.androidtv.ui.presentation.ComposeBrowseListPresenter
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

})
