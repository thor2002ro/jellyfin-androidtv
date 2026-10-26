package org.jellyfin.androidtv.ui.home

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.geometry.Rect
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.RowPresenter
import androidx.leanback.widget.VerticalGridView
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.jellyfin.androidtv.ui.shared.transition.ProfileLoginAnimation
import org.jellyfin.androidtv.ui.shared.transition.ProfileLoginOrigin

class HomeProfileTransitionTests : FunSpec({
	test("profile loading ring lasts two seconds from selection without restarting at handoff") {
		val bounds = Rect(0f, 0f, 100f, 100f)
		val origin = ProfileLoginOrigin(bounds, bounds, "User", null, 20f, selectedAtMillis = 2_000L)

		origin.remainingLoadingMillis(2_000L) shouldBe 2_000L
		origin.remainingLoadingMillis(2_750L) shouldBe 1_250L
		origin.remainingLoadingMillis(4_000L) shouldBe 0L
		origin.remainingLoadingMillis(5_000L) shouldBe 0L
		origin.remainingLoadingMillis(1_000L) shouldBe 2_000L
	}

	test("profile movement is ready before Home cards finish loading") {
		val bounds = Rect(0f, 0f, 100f, 100f)
		val animation = ProfileLoginAnimation(ProfileLoginOrigin(bounds, bounds, "User", null, 20f))
		animation.avatarTarget = Rect(20f, 20f, 40f, 40f)
		animation.nameTarget = Rect(50f, 20f, 130f, 40f)
		animation.nameFontSizeTargetPx = 14f

		animation.homeRowsReady shouldBe false
		animation.isReady shouldBe true
	}

	test("profile handoff waits for wrapped-row cards to finish their first draw") {
		val grid = mockk<VerticalGridView>(relaxed = true)
		val observer = mockk<ViewTreeObserver>(relaxed = true)
		val preDraw = slot<ViewTreeObserver.OnPreDrawListener>()
		val afterDraw = slot<Runnable>()
		val child = mockk<View>()
		val bridge = mockk<ItemBridgeAdapter.ViewHolder>()
		val presenter = mockk<RowPresenter>()
		val container = Presenter.ViewHolder(mockk())
		val row = mockk<ListRowPresenter.ViewHolder>(relaxed = true)
		every { grid.viewTreeObserver } returns observer
		every { observer.isAlive } returns true
		every { observer.addOnPreDrawListener(capture(preDraw)) } returns Unit
		every { grid.post(capture(afterDraw)) } returns true
		every { grid.getChildAt(0) } returns child
		every { grid.getChildViewHolder(child) } returns bridge
		every { bridge.presenter } returns presenter
		every { bridge.viewHolder } returns container
		every { presenter.getRowViewHolder(container) } returns row
		every { row.gridView.childCount } returnsMany listOf(0, 1)
		val bounds = Rect(0f, 0f, 100f, 100f)
		val animation = ProfileLoginAnimation(ProfileLoginOrigin(bounds, bounds, "User", null, 20f))

		grid.prepareProfileTransition(animation)
		preDraw.captured.onPreDraw()
		animation.homeRowsReady shouldBe false
		preDraw.captured.onPreDraw()
		animation.homeRowsReady shouldBe false
		afterDraw.captured.run()
		animation.homeRowsReady shouldBe true
	}
})
