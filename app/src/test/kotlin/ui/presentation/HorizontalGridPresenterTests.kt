package org.jellyfin.androidtv.ui.presentation

import android.view.ViewGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.leanback.widget.Presenter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.constant.LibraryCardSpacing

class HorizontalGridPresenterTests : FunSpec({
	test("shared card grid selection notifies only when the selected item changes") {
		val presenter = TestBrowseCardGridPresenter()
		val selected = mutableListOf<Any?>()
		presenter.setOnItemViewSelectedListener { _, item, _, _ -> selected += item }
		val first = Any()
		val replacement = Any()

		presenter.select(position = 4, item = first)
		presenter.select(position = 4, item = first)
		presenter.select(position = 4, item = replacement)

		selected.shouldBe(listOf(first, replacement))
		presenter.getPosition().shouldBe(4)
	}

	test("vertical and horizontal card grids share selection position behavior") {
		val presenters: List<BrowseCardGridPresenter> = listOf(
			HorizontalGridPresenter(),
			ComposeVerticalGridPresenter(),
		)

		presenters.forEach { presenter ->
			presenter.setPosition(37)
			presenter.setPosition(-1)
			presenter.getPosition().shouldBe(37)
		}
	}

	test("vertical and horizontal card grids share normalized layout configuration") {
		val presenters: List<BrowseCardGridPresenter> = listOf(
			HorizontalGridPresenter(),
			ComposeVerticalGridPresenter(),
		)

		presenters.forEach { presenter ->
			presenter.configure(
				imageType = ImageType.POSTER,
				cardHeight = 180,
				spanCount = 0,
				horizontalSpacing = 8,
				verticalSpacing = 6,
				showCardTitles = true,
				paddingStart = 12,
				paddingEnd = 14,
				verticalPadding = 16,
			)

			presenter.getSpanCount().shouldBe(1)
		}
	}

	test("only the focused item receives the restore focus requester") {
		val requester = FocusRequester()

		Modifier.restoreFocusRequesterWhen(focused = false, requester) shouldBe Modifier
		Modifier.restoreFocusRequesterWhen(focused = true, requester) shouldNotBe Modifier
	}

	test("only cards in the actual top row leave a horizontal grid on up") {
		val visibleTops = listOf(10, 260, 510, 10, 260, 510)

		isTopHorizontalGridRow(selectedTop = 10, visibleTops) shouldBe true
		isTopHorizontalGridRow(selectedTop = 260, visibleTops) shouldBe false
		isTopHorizontalGridRow(selectedTop = 510, visibleTops) shouldBe false
	}

	test("compact card spacing only changes the gap between cards") {
		resolveBrowseGridSpacing(8, LibraryCardSpacing.COMPACT) shouldBe 6
	}

	test("selection notification changes when the item at the same position is replaced") {
		val tracker = GridSelectionNotificationTracker()
		val first = Any()
		val replacement = Any()

		tracker.shouldNotify(position = 4, item = first).shouldBe(true)
		tracker.shouldNotify(position = 4, item = first).shouldBe(false)
		tracker.shouldNotify(position = 4, item = replacement).shouldBe(true)
	}

	test("adapter change bursts schedule one snapshot sync") {
		val gate = GridAdapterSyncGate()
		val posted = mutableListOf<() -> Unit>()
		var syncs = 0

		repeat(3) {
			gate.request(
				post = { posted += it },
				sync = { syncs++ },
			)
		}

		posted.size.shouldBe(1)
		syncs.shouldBe(0)
		posted.single().invoke()
		syncs.shouldBe(1)

		gate.request(
			post = { posted += it },
			sync = { syncs++ },
		)
		posted.size.shouldBe(2)
	}

	test("cancelled adapter sync cannot update a replacement grid") {
		val gate = GridAdapterSyncGate()
		val posted = mutableListOf<() -> Unit>()
		var syncs = 0

		gate.request(post = { posted += it }, sync = { syncs++ })
		gate.cancel()
		gate.request(post = { posted += it }, sync = { syncs++ })

		posted[0].invoke()
		syncs.shouldBe(0)
		posted[1].invoke()
		syncs.shouldBe(1)
	}

	test("repeated updates from the same adapter keep the existing grid attachment") {
		val state = HorizontalGridPresenter.State()
		val adapter = Any()

		state.bindAdapter(adapter).shouldBe(true)
		state.bindAdapter(adapter).shouldBe(false)
	}

	test("unbinding permits the same adapter to attach to a replacement grid") {
		val state = HorizontalGridPresenter.State()
		val adapter = Any()

		state.bindAdapter(adapter).shouldBe(true)
		state.unbindAdapter()
		state.bindAdapter(adapter).shouldBe(true)
	}
})

private class TestBrowseCardGridPresenter : BrowseCardGridPresenter() {
	override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder = error("Not used")
	override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) = Unit
	override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) = Unit

	fun select(position: Int, item: Any) = notifyItemSelected(null, position, item)
}
