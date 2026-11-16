package org.jellyfin.androidtv.ui.browsing

import android.view.View
import android.content.Context
import android.view.ViewGroup
import android.view.KeyEvent
import androidx.leanback.widget.Presenter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import org.jellyfin.androidtv.data.model.FilterOptions
import org.jellyfin.androidtv.data.model.PlaybackFilter
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.constant.GridDirection
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.constant.LibraryViewStyle
import org.jellyfin.androidtv.constant.LibraryCardSpacing
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
	test("music posters remain square while other poster and thumbnail dimensions retain their ratios") {
		val music = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.COLLECTION_FOLDER, collectionType = CollectionType.MUSIC)
		val movies = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.USER_VIEW, collectionType = CollectionType.MOVIES)
		getCardWidthBy(90.0, ImageType.POSTER, music) shouldBe 90.0
		getCardHeightBy(90.0, ImageType.POSTER, music) shouldBe 90.0
		getCardWidthBy(90.0, ImageType.POSTER, movies) shouldBe 60.0
		getCardWidthBy(90.0, ImageType.THUMB, movies) shouldBe 160.0
		getCardHeightBy(160.0, ImageType.THUMB, movies) shouldBe 90.0
	}

	test("grid sizing preserves card layout and visible item estimates") {
		val library = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.USER_VIEW, collectionType = CollectionType.MOVIES)
		val horizontal = HorizontalGridPresenter().apply { setSpanCount(3) }
		val vertical = ComposeVerticalGridPresenter().apply { setSpanCount(5) }
		for (presenter in listOf(horizontal, vertical)) {
			val layout = calculateBrowseGridLayout(presenter, 1920, 900, 1.05, ImageType.POSTER, library, false, LibraryCardSpacing.NORMAL)
			(layout.cardHeight > 0) shouldBe true
			(layout.cardsScreenEstimate >= layout.cardsScreenStride) shouldBe true
			(layout.cardsScreenStride > 0) shouldBe true
		}
		val list = calculateBrowseGridLayout(ComposeBrowseListPresenter(), 1920, 900, 1.05, ImageType.POSTER, library, false, LibraryCardSpacing.NORMAL)
		list.horizontalSpacing shouldBe 0
		list.cardsScreenStride shouldBe list.cardsScreenEstimate
	}
	test("only horizontal cards use the separate library backdrop") {
		shouldDrawBrowseBackground(LibraryViewStyle.CARDS, GridDirection.HORIZONTAL) shouldBe true
		shouldDrawBrowseBackground(LibraryViewStyle.CARDS, GridDirection.VERTICAL) shouldBe false
		for (direction in GridDirection.entries) {
			shouldDrawBrowseBackground(LibraryViewStyle.DENSE_LIST, direction) shouldBe false
			shouldDrawBrowseBackground(LibraryViewStyle.KALEIDESCAPE_STYLE, direction) shouldBe false
		}
	}

	test("vertical counter rotates within its bottom right corner and resets for horizontal view") {
		val counter = mockk<View>(relaxed = true)
		every { counter.width } returns 120
		every { counter.height } returns 40
		configureFooterRotation(counter, true, 0)
		verify {
			counter.rotation = 90f
			counter.pivotX = 120f
			counter.pivotY = 0f
			counter.translationY = 40f
		}
		configureFooterRotation(counter, true, 16)
		verify { counter.translationY = 24f }
		configureFooterRotation(counter, false, 16)
		verify {
			counter.rotation = 0f
			counter.translationY = 0f
		}
	}
	test("rotated status fits between the toolbar and counter") {
		getVerticalStatusWidth(400, 1080, 120, 16) shouldBe 528
		getVerticalStatusWidth(400, 480, 120, 16) shouldBe 0
	}
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

	test("library sort choices preserve show and movie specific fields") {
		val context = mockk<Context>()
		every { context.getString(any()) } answers { firstArg<Int>().toString() }
		val shows = browseSortOptions(context, CollectionType.TVSHOWS)
		val movies = browseSortOptions(context, CollectionType.MOVIES)
		shows[6]?.value shouldBe ItemSortBy.SERIES_DATE_PLAYED
		shows[7]?.value shouldBe ItemSortBy.DATE_LAST_CONTENT_ADDED
		movies[6]?.value shouldBe ItemSortBy.DATE_PLAYED
		movies[7]?.value shouldBe ItemSortBy.RUNTIME
		browseSortOptions(context, CollectionType.MUSIC).containsKey(7) shouldBe false
	}

	test("safe selected position never returns invalid leanback positions") {
		getSafeSelectedPosition(-1, -1, -1, 125) shouldBe 0
		getSafeSelectedPosition(21, 6, 6, 80) shouldBe 21
		getSafeSelectedPosition(150, -1, -1, 125) shouldBe 124
		getSafeSelectedPosition(-1, 150, -1, 125) shouldBe 124
		getSafeSelectedPosition(-1, -1, 150, 125) shouldBe 124
		getSafeSelectedPosition(-1, -1, -1, 0) shouldBe -1
	}

	test("returning to a populated library reuses its loaded items") {
		shouldRetrieveGrid(false, false, 80) shouldBe false
	}

	test("initial empty rebuilt and query-changed libraries retrieve items") {
		shouldRetrieveGrid(false, false, 0) shouldBe true
		shouldRetrieveGrid(true, false, 80) shouldBe true
		shouldRetrieveGrid(false, true, 80) shouldBe true
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
		createGridPresenter(GridDirection.VERTICAL).javaClass shouldBe ComposeVerticalGridPresenter::class.java
	}

	test("horizontal browse uses the Compose grid presenter") {
		createGridPresenter(GridDirection.HORIZONTAL).javaClass shouldBe HorizontalGridPresenter::class.java
	}

	test("dense list overrides the saved card direction") {
		createGridPresenter(
			GridDirection.HORIZONTAL,
			LibraryViewStyle.DENSE_LIST,
		).javaClass shouldBe ComposeBrowseListPresenter::class.java
	}

	test("dense list uses vertical navigation controls while cards honor their direction") {
		effectiveNavigationControlsDirection(
			LibraryViewStyle.DENSE_LIST,
			GridDirection.HORIZONTAL,
		) shouldBe GridDirection.VERTICAL
		effectiveNavigationControlsDirection(
			LibraryViewStyle.CARDS,
			GridDirection.HORIZONTAL,
		) shouldBe GridDirection.HORIZONTAL
	}

	test("fresh sort and filter queries choose only valid positions") {
		nextSelectionAfterQueryChange(50) shouldBe 0
		nextSelectionAfterQueryChange(0) shouldBe -1
	}

	test("empty results keep filter clearing reachable") {
		shouldKeepToolbarFocusable(0, true) shouldBe true
		shouldKeepToolbarFocusable(0, false) shouldBe false
	}

	test("watched filter removes an item refreshed as unplayed") {
		val filters = FilterOptions(playback = PlaybackFilter.WATCHED)

		shouldRemoveFilteredItem(filters, true, false) shouldBe true
		shouldRemoveFilteredItem(filters, true, true) shouldBe false
	}

	test("unchanged filters do not start another library query") {
		val current = FilterOptions(playback = PlaybackFilter.UNWATCHED)

		shouldApplyLibraryFilters(current, current.copy()) shouldBe false
		shouldApplyLibraryFilters(current, FilterOptions()) shouldBe true
	}

	test("vertical browse leaves room for the alphabet rail while horizontal stays edge aligned") {
		getGridHostSideMarginDp(GridDirection.VERTICAL) shouldBe 20
		getGridHostSideMarginDp(GridDirection.HORIZONTAL) shouldBe 0
	}

	test("vertical browse leaves room for the customization rail") {
		getGridHostEndMarginDp(GridDirection.VERTICAL) shouldBe 26
		getGridHostEndMarginDp(GridDirection.HORIZONTAL) shouldBe 0
	}

	test("horizontal browse converts XML pixel insets before passing them to Compose") {
		pixelsToDp(100, 2f) shouldBe 50
	}

	test("horizontal card titles fit inside every grid row") {
		getHorizontalBrowseCardHeight(124, false) shouldBe 124
		getHorizontalBrowseCardHeight(124, true) shouldBe 98
	}

	test("browse grid keeps the appropriate bottom control band clear of cards") {
		getGridHostBottomMarginDp(GridDirection.HORIZONTAL) shouldBe 32
		getGridHostBottomMarginDp(GridDirection.VERTICAL) shouldBe 0
	}

	test("alphabet rail follows the grid direction without a sort gate") {
		shouldShowAlphabetPicker(GridDirection.HORIZONTAL, GridDirection.HORIZONTAL) shouldBe true
		shouldShowAlphabetPicker(GridDirection.VERTICAL, GridDirection.VERTICAL) shouldBe true
		shouldShowAlphabetPicker(GridDirection.HORIZONTAL, GridDirection.VERTICAL) shouldBe false
		shouldShowAlphabetPicker(GridDirection.VERTICAL, GridDirection.HORIZONTAL) shouldBe false
	}

	test("horizontal grid handoff routes up to customization") {
		shouldMoveFocusFromGridToToolbar(
			GridDirection.HORIZONTAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_UP,
		) shouldBe true
		shouldMoveFocusFromGridToToolbar(
			GridDirection.HORIZONTAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_DOWN,
		) shouldBe false
		shouldMoveFocusFromGridToToolbar(
			GridDirection.HORIZONTAL,
			KeyEvent.ACTION_UP,
			KeyEvent.KEYCODE_DPAD_UP,
		) shouldBe false
		shouldMoveFocusFromGridToToolbar(
			GridDirection.VERTICAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_UP,
		) shouldBe false
	}

	test("vertical grid handoff routes up to home") {
		shouldMoveFocusFromGridToHome(
			GridDirection.VERTICAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_UP,
		) shouldBe true
		shouldMoveFocusFromGridToHome(
			GridDirection.VERTICAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_DOWN,
		) shouldBe false
		shouldMoveFocusFromGridToHome(
			GridDirection.HORIZONTAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_UP,
		) shouldBe false
	}

	test("custom navigation control focus routing is horizontal only") {
		shouldConfigureNavigationControlsFocus(GridDirection.HORIZONTAL) shouldBe true
		shouldConfigureNavigationControlsFocus(GridDirection.VERTICAL) shouldBe false
	}

	test("horizontal customization routes up to home") {
		shouldMoveFocusFromToolbarToHome(
			GridDirection.HORIZONTAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_UP,
		) shouldBe true
		shouldMoveFocusFromToolbarToHome(
			GridDirection.HORIZONTAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_DOWN,
		) shouldBe false
		shouldMoveFocusFromToolbarToHome(
			GridDirection.VERTICAL,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_DPAD_UP,
		) shouldBe false
	}

	test("replacing a browse grid releases its bound presenter") {
		val presenter = TrackingGridPresenter()
		val holder = Presenter.ViewHolder(mockk<View>(relaxed = true))
		presenter.onBindViewHolder(holder, Unit)

		releaseGrid(presenter, holder)

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
