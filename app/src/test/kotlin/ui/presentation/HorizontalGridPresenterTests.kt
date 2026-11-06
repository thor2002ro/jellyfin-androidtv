package org.jellyfin.androidtv.ui.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.constant.LibraryCardSpacing

class HorizontalGridPresenterTests : FunSpec({
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

	test("initial horizontal position starts at the restored item") {
		findHorizontalGridInitialItemIndex(itemCount = 50, selectedPosition = 37).shouldBe(37)
	}

	test("initial horizontal position clamps a stale selection") {
		findHorizontalGridInitialItemIndex(itemCount = 12, selectedPosition = 37).shouldBe(11)
		findHorizontalGridInitialItemIndex(itemCount = 0, selectedPosition = 37).shouldBe(0)
	}

	test("repeated updates from the same adapter keep the existing grid attachment") {
		val state = HorizontalGridPresenter.State()
		val adapter = Any()

		state.bindAdapter(adapter).shouldBe(true)
		state.bindAdapter(adapter).shouldBe(false)
	}

	test("a new grid attachment receives the last requested position") {
		val state = HorizontalGridPresenter.State()

		state.setPosition(37)
		state.position.shouldBe(37)
		state.unbindAdapter()
		state.position.shouldBe(37)
	}

	test("unbinding permits the same adapter to attach to a replacement grid") {
		val state = HorizontalGridPresenter.State()
		val adapter = Any()

		state.bindAdapter(adapter).shouldBe(true)
		state.unbindAdapter()
		state.bindAdapter(adapter).shouldBe(true)
	}
})
