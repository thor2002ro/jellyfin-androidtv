package org.jellyfin.androidtv.ui.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

class ComposeVerticalGridPresenterTests : FunSpec({
	fun folder(aspectRatio: Double) = BaseItemDtoBaseRowItem(
		BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.FOLDER,
			primaryImageAspectRatio = aspectRatio,
		)
	)

	test("single width card immediately after a wide card fills the last column") {
		val packed = packBrowseGridItems(
			items = listOf(folder(0.75), folder(0.75), folder(0.75), folder(1.78), folder(0.75), folder(1.78)),
			columns = 4,
			imageType = ImageType.POSTER,
		)

		packed.map { it.adapterPosition }.shouldContainExactly(0, 1, 2, 4, 3, 5)
		packed.map { it.row to it.column }.shouldContainExactly(0 to 0, 0 to 1, 0 to 2, 0 to 3, 1 to 0, 1 to 2)
	}

	test("wide card does not search past another wide card for a gap filler") {
		val packed = packBrowseGridItems(
			items = listOf(folder(0.75), folder(0.75), folder(0.75), folder(1.78), folder(1.78), folder(0.75)),
			columns = 4,
			imageType = ImageType.POSTER,
		)

		packed.map { it.adapterPosition }.shouldContainExactly(0, 1, 2, 3, 4, 5)
		packed.map { it.span }.shouldContainExactly(1, 1, 1, 2, 2, 1)
		findBrowseGridFocusTarget(packed, 1, BrowseGridFocusDirection.DOWN).shouldBe(3)
		findBrowseGridFocusTarget(packed, 3, BrowseGridFocusDirection.RIGHT).shouldBe(4)
		findBrowseGridInitialVisualPosition(packed, selectedPosition = 3).shouldBe(3)
		findBrowseGridInitialVisualPosition(packed, selectedPosition = 99).shouldBe(0)
	}

	test("wide configured image types keep one configured column per item") {
		val item = BaseItemDtoBaseRowItem(
			BaseItemDto(
				id = UUID.randomUUID(),
				type = BaseItemKind.FOLDER,
				primaryImageAspectRatio = 1.78,
			)
		)

		packBrowseGridItems(
			items = listOf(item),
			columns = 4,
			imageType = ImageType.THUMB,
		).map { it.span }.shouldContainExactly(1)
	}

	test("vertical grid viewport uses all available height so cards reach the bottom controls") {
		calculateBrowseGridViewportHeight(
			maxHeight = 437f,
		).shouldBe(437f)
	}

	test("one directional key event can move focus only once") {
		val guard = BrowseGridDirectionalKeyGuard()

		guard.tryAccept(100L).shouldBe(true)
		guard.tryAccept(100L).shouldBe(false)
		guard.tryAccept(101L).shouldBe(true)
	}

	test("vertical grid lets focus leave through side rails but contains vertical bounds") {
		shouldConsumeMissingBrowseGridTarget(BrowseGridFocusDirection.LEFT).shouldBe(false)
		shouldConsumeMissingBrowseGridTarget(BrowseGridFocusDirection.RIGHT).shouldBe(false)
		shouldConsumeMissingBrowseGridTarget(BrowseGridFocusDirection.DOWN).shouldBe(true)
		shouldConsumeMissingBrowseGridTarget(BrowseGridFocusDirection.UP).shouldBe(true)
	}

	test("attached focus target moves directly without queuing a scroll request") {
		var queuedPosition: Int? = null

		requestBrowseGridFocus(
			position = 37,
			directRequest = { true },
			enqueueRequest = { queuedPosition = it },
		).shouldBe(true)
		queuedPosition.shouldBe(null)
	}

	test("unattached focus target queues the existing scroll request") {
		var queuedPosition: Int? = null

		requestBrowseGridFocus(
			position = 37,
			directRequest = { false },
			enqueueRequest = { queuedPosition = it },
		).shouldBe(false)
		queuedPosition.shouldBe(37)
	}

	test("precomposed focus target outside the viewport uses Compose focus scrolling") {
		var directRequestCalled = false
		var queuedPosition: Int? = null

		requestBrowseGridFocus(
			position = 37,
			directRequest = {
				directRequestCalled = true
				true
			},
			enqueueRequest = { queuedPosition = it },
		).shouldBe(true)
		directRequestCalled.shouldBe(true)
		queuedPosition.shouldBe(null)
	}
})
