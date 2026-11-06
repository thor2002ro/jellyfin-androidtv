package org.jellyfin.androidtv.ui.presentation

import android.view.KeyEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.constant.LibraryViewStyle

class ComposeBrowseListPresenterTests : FunSpec({
	test("dense list initial position clamps stale selections") {
		findBrowseListInitialIndex(itemCount = 12, selectedPosition = 37) shouldBe 11
		findBrowseListInitialIndex(itemCount = 0, selectedPosition = 37) shouldBe 0
	}

	test("dense list replacement preserves selection and permits adapter reattachment") {
		val state = BrowseListPresenterState()
		val adapter = Any()

		state.setPosition(8)
		state.bindAdapter(adapter) shouldBe true
		state.unbindAdapter()
		state.position shouldBe 8
		state.bindAdapter(adapter) shouldBe true
	}

	test("dense list lets focus leave through both horizontal edges") {
		shouldConsumeMissingBrowseListTarget(BrowseGridFocusDirection.LEFT) shouldBe false
		shouldConsumeMissingBrowseListTarget(BrowseGridFocusDirection.RIGHT) shouldBe false
	}

	test("dense list forwards up to the surrounding navigation controls only from its first item") {
		shouldForwardBrowseListKey(position = 0, KeyEvent.KEYCODE_DPAD_UP) shouldBe true
		shouldForwardBrowseListKey(position = 1, KeyEvent.KEYCODE_DPAD_UP) shouldBe false
		shouldForwardBrowseListKey(position = 0, KeyEvent.KEYCODE_DPAD_DOWN) shouldBe false
	}

	test("dense list rendering and prefetch share the same image request size") {
		resolveBrowseImageRequestSize(
			style = LibraryViewStyle.DENSE_LIST,
			cardWidthDp = 135,
			cardHeightDp = 76,
		) shouldBe BrowseImageRequestSize(widthDp = 136, heightDp = 76)
		resolveBrowseImageRequestSize(
			style = LibraryViewStyle.CARDS,
			cardWidthDp = 135,
			cardHeightDp = 200,
		) shouldBe BrowseImageRequestSize(widthDp = 135, heightDp = 200)
	}
})
